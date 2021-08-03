// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.store.dao

import java.sql.{Connection, SQLTransientConnectionException}
import java.util.concurrent.atomic.AtomicInteger
import java.util.{Timer, TimerTask}

import com.codahale.metrics.MetricRegistry
import com.daml.ledger.api.health.{HealthStatus, Healthy, Unhealthy}
import com.daml.ledger.resources.{Resource, ResourceContext, ResourceOwner}
import com.daml.logging.{ContextualizedLogger, LoggingContext}
import com.daml.metrics.{DatabaseMetrics, Timed}
import com.daml.platform.configuration.ServerRole
import com.daml.platform.store.DbType
import com.daml.platform.store.dao.HikariJdbcConnectionProvider._
import com.daml.timer.RetryStrategy
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.control.NonFatal

private[platform] final class HikariConnection(
    serverRole: ServerRole,
    jdbcUrl: String,
    minimumIdle: Int,
    maxPoolSize: Int,
    connectionTimeout: FiniteDuration,
    metrics: Option[MetricRegistry],
    connectionPoolPrefix: String,
    maxInitialConnectRetryAttempts: Int,
    connectionAsyncCommitMode: DbType.AsyncCommitMode,
)(implicit loggingContext: LoggingContext)
    extends ResourceOwner[HikariDataSource] {

  private val logger = ContextualizedLogger.get(this.getClass)

  override def acquire()(implicit context: ResourceContext): Resource[HikariDataSource] = {
    val config = new HikariConfig
    val dbType = DbType.jdbcType(jdbcUrl)

    config.setJdbcUrl(jdbcUrl)
    config.setDriverClassName(dbType.driver)
    config.addDataSourceProperty("cachePrepStmts", "true")
    config.addDataSourceProperty("prepStmtCacheSize", "128")
    config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
    config.setAutoCommit(false)
    config.setMaximumPoolSize(maxPoolSize)
    config.setMinimumIdle(minimumIdle)
    config.setConnectionTimeout(connectionTimeout.toMillis)
    config.setPoolName(s"$connectionPoolPrefix.${serverRole.threadPoolSuffix}")
    metrics.foreach(config.setMetricRegistry)

    configureAsyncCommit(config, dbType)

    // Hikari dies if a database connection could not be opened almost immediately
    // regardless of any connection timeout settings. We retry connections so that
    // Postgres and Sandbox can be started in any order.
    Resource(
      RetryStrategy.constant(
        attempts = maxInitialConnectRetryAttempts,
        waitTime = 1.second,
      ) { (i, _) =>
        Future {
          logger.info(
            s"Attempting to connect to the database (attempt $i/$maxInitialConnectRetryAttempts)"
          )
          new HikariDataSource(config)
        }
      }
    )(conn => Future { conn.close() })
  }

  private def configureAsyncCommit(config: HikariConfig, dbType: DbType): Unit =
    if (dbType.supportsAsynchronousCommits) {
      logger.info(
        s"Creating Hikari connections with synchronous commit ${connectionAsyncCommitMode.setting}"
      )
      config.setConnectionInitSql(s"SET synchronous_commit=${connectionAsyncCommitMode.setting}")
    } else if (connectionAsyncCommitMode != DbType.SynchronousCommit) {
      logger.warn(
        s"Asynchronous commit setting ${connectionAsyncCommitMode.setting} is not compatible with ${dbType.name} database backend"
      )
    }
}

private[platform] object HikariConnection {
  private val MaxInitialConnectRetryAttempts = 600
  private val ConnectionPoolPrefix: String = "daml.index.db.connection"

  def owner(
      serverRole: ServerRole,
      jdbcUrl: String,
      minimumIdle: Int,
      maxPoolSize: Int,
      connectionTimeout: FiniteDuration,
      metrics: Option[MetricRegistry],
      connectionAsyncCommitMode: DbType.AsyncCommitMode,
  )(implicit loggingContext: LoggingContext): HikariConnection =
    new HikariConnection(
      serverRole,
      jdbcUrl,
      minimumIdle,
      maxPoolSize,
      connectionTimeout,
      metrics,
      ConnectionPoolPrefix,
      MaxInitialConnectRetryAttempts,
      connectionAsyncCommitMode,
    )
}

private[platform] class HikariJdbcConnectionProvider(
    dataSource: HikariDataSource,
    healthPoller: Timer,
) extends JdbcConnectionProvider {
  private val transientFailureCount = new AtomicInteger(0)

  private val checkHealth = new TimerTask {
    override def run(): Unit = {
      try {
        dataSource.getConnection().close()
        transientFailureCount.set(0)
      } catch {
        case _: SQLTransientConnectionException =>
          val _ = transientFailureCount.incrementAndGet()
      }
    }
  }

  healthPoller.schedule(checkHealth, 0, HealthPollingSchedule.toMillis)

  override def currentHealth(): HealthStatus =
    if (transientFailureCount.get() < MaxTransientFailureCount)
      Healthy
    else
      Unhealthy

  override def runSQL[T](databaseMetrics: DatabaseMetrics, isolationLevel: Option[Int])(
      block: Connection => T
  ): T = {
    val conn = dataSource.getConnection()
    isolationLevel.foreach(level => {
      // setTransactionIsolation() can only be used outside of a transaction.
      // With auto-commit disabled (which is our default), the connection is already in an open transaction.
      // We therefore need to enable auto-commit before change the isolation level.
      conn.setAutoCommit(true)
      // Hikari resets the isolation level when recycling connections, this call won't pollute the connection pool.
      conn.setTransactionIsolation(level)
    })
    conn.setAutoCommit(false)
    try {
      val res = Timed.value(
        databaseMetrics.queryTimer,
        block(conn),
      )
      Timed.value(
        databaseMetrics.commitTimer,
        conn.commit(),
      )
      res
    } catch {
      case e: SQLTransientConnectionException =>
        transientFailureCount.incrementAndGet()
        throw e
      case NonFatal(t) =>
        // Log the error in the caller with access to more logging context (such as the sql statement description)
        conn.rollback()
        throw t
    } finally {
      conn.close()
    }
  }
}

private[platform] object HikariJdbcConnectionProvider {
  private val MaxTransientFailureCount: Int = 5
  private val HealthPollingSchedule: FiniteDuration = 1.second

  def owner(
      serverRole: ServerRole,
      jdbcUrl: String,
      maxConnections: Int,
      connectionTimeout: FiniteDuration,
      metrics: MetricRegistry,
      connectionAsyncCommitMode: DbType.AsyncCommitMode = DbType.SynchronousCommit,
  )(implicit loggingContext: LoggingContext): ResourceOwner[HikariJdbcConnectionProvider] =
    for {
      // these connections should never time out as we have the same number of threads as connections
      dataSource <- HikariConnection.owner(
        serverRole,
        jdbcUrl,
        maxConnections,
        maxConnections,
        connectionTimeout,
        Some(metrics),
        connectionAsyncCommitMode,
      )
      healthPoller <- ResourceOwner.forTimer(() =>
        new Timer(s"${classOf[HikariJdbcConnectionProvider].getName}#healthPoller")
      )
    } yield new HikariJdbcConnectionProvider(dataSource, healthPoller)
}
