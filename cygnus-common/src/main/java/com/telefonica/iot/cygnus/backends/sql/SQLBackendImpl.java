/**
 * Copyright 2014-2017 Telefonica Investigación y Desarrollo, S.A.U
 *
 * This file is part of fiware-cygnus (FIWARE project).
 *
 * fiware-cygnus is free software: you can redistribute it and/or modify it under the terms of the GNU Affero
 * General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 * fiware-cygnus is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the
 * implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with fiware-cygnus. If not, see
 * http://www.gnu.org/licenses/.
 *
 * For those usages not covered by the GNU Affero General Public License please contact with iot_support at tid dot es
 */

package com.telefonica.iot.cygnus.backends.sql;

import com.sun.rowset.CachedRowSetImpl;
import com.telefonica.iot.cygnus.errors.CygnusBadContextData;
import com.telefonica.iot.cygnus.errors.CygnusPersistenceError;
import com.telefonica.iot.cygnus.errors.CygnusRuntimeError;
import com.telefonica.iot.cygnus.log.CygnusLogger;
import com.telefonica.iot.cygnus.utils.CommonUtils;
import org.apache.commons.dbcp.ConnectionFactory;
import org.apache.commons.dbcp.DriverManagerConnectionFactory;
import org.apache.commons.dbcp.PoolableConnectionFactory;
import org.apache.commons.dbcp.PoolingDataSource;
import org.apache.commons.pool.impl.GenericObjectPool;

import javax.sql.DataSource;
import javax.sql.rowset.CachedRowSet;
import java.sql.*;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;

public class SQLBackendImpl implements SQLBackend{

    private static final CygnusLogger LOGGER = new CygnusLogger(SQLBackendImpl.class);
    private SQLBackendImpl.SQLDriver driver;
    private final SQLCache cache;
    private final String sqlInstance;

    /**
     * Constructor.
     *
     * @param sqlHost
     * @param sqlPort
     * @param sqlUsername
     * @param sqlPassword
     */
    public SQLBackendImpl(String sqlHost, String sqlPort, String sqlUsername, String sqlPassword, int maxPoolSize, String sqlInstance, String sqlDriverName, String defaultSQLDataBase, String sqlMode) {
        driver = new SQLBackendImpl.SQLDriver(sqlHost, sqlPort, sqlUsername, sqlPassword, maxPoolSize, sqlInstance, sqlDriverName, defaultSQLDataBase, sqlMode);
        cache = new SQLCache();
        this.sqlInstance = sqlInstance;
    } // SQLBackendImpl

    /**
     * Constructor.
     * 
     * @param sqlHost
     * @param sqlPort
     * @param sqlUsername
     * @param sqlPassword
     * @param maxPoolSize
     * @param sqlInstance
     * @param sqlDriverName
     * @param defaultSQLDataBase
     */
    public SQLBackendImpl(String sqlHost, String sqlPort, String sqlUsername, String sqlPassword, int maxPoolSize, String sqlInstance, String sqlDriverName, String defaultSQLDataBase) {
        this(sqlHost, sqlPort, sqlUsername, sqlPassword, maxPoolSize, sqlInstance, sqlDriverName, defaultSQLDataBase, "");
    }
    /**
     * Releases resources
     */
    public void close(){
        if (driver != null) driver.close();
    } // close

    /**
     * Sets the SQL driver. It is protected since it is only used by the
     * tests.
     *
     * @param driver The SQL driver to be set.
     */
    public void setDriver(SQLBackendImpl.SQLDriver driver) {
        this.driver = driver;
    } // setDriver

    public SQLBackendImpl.SQLDriver getDriver() {
        return driver;
    } // getDriver

    @Override
    public void createDestination(String destination) throws CygnusRuntimeError, CygnusPersistenceError {
        if (cache.isCachedDestination(destination)) {
            LOGGER.debug(sqlInstance.toUpperCase() + " '" + destination + "' is cached, thus it is not created");
            return;
        } // if

        Statement stmt = null;

        // get a connection to an empty destination
        Connection con = driver.getConnection("");

        String query = "";
        if (sqlInstance.equals("mysql")) {
            query = "create database if not exists `" + destination + "`";
        } else {
            query = "CREATE SCHEMA IF NOT EXISTS " + destination;
        }

        try {
            stmt = con.createStatement();
        } catch (SQLException e) {
            closeSQLObjects(con, stmt);
            throw new CygnusRuntimeError(sqlInstance.toUpperCase() + " Database/scheme creation error", "SQLException", e.getMessage());
        } // try catch

        try {
            LOGGER.debug(sqlInstance.toUpperCase() + " Executing SQL query '" + query + "'");
            stmt.executeUpdate(query);
        } catch (SQLException e) {
            closeSQLObjects(con, stmt);
            throw new CygnusPersistenceError(sqlInstance.toUpperCase() + " Database/scheme creation error", "SQLException", e.getMessage());
        } // try catch

        closeSQLObjects(con, stmt);

        LOGGER.debug(sqlInstance.toUpperCase() + " Trying to add '" + destination + "' to the cache after database/scheme creation");
        cache.addDestination(destination);
    } // createDestination

    @Override
    public void createTable(String destination, String tableName, String typedFieldNames)
            throws CygnusRuntimeError, CygnusPersistenceError {
        if (cache.isCachedTable(destination, tableName)) {
            LOGGER.debug(sqlInstance.toUpperCase() + " '" + tableName + "' is cached, thus it is not created");
            return;
        } // if

        Statement stmt = null;

        // get a connection to the given destination
        Connection con = driver.getConnection(destination);
        String query = "";
        if (sqlInstance.equals("mysql")) {
            query = "create table if not exists `" + tableName + "`" + typedFieldNames;
        } else {
            query = "CREATE TABLE IF NOT EXISTS " + destination + "." + tableName + " " + typedFieldNames;
        }

        try {
            stmt = con.createStatement();
        } catch (SQLException e) {
            closeSQLObjects(con, stmt);
            throw new CygnusRuntimeError(sqlInstance.toUpperCase() + " Table creation error", "SQLException", e.getMessage());
        } // try catch

        try {
            LOGGER.debug(sqlInstance.toUpperCase() + " Executing SQL query '" + query + "'");
            stmt.executeUpdate(query);
        } catch (SQLTimeoutException e) {
            throw new CygnusPersistenceError(sqlInstance.toUpperCase() + " Table creation error. Query " + query, "SQLTimeoutException", e.getMessage());
        } catch (SQLException e) {
            closeSQLObjects(con, stmt);
            persistError(destination, query, e);
            throw new CygnusPersistenceError(sqlInstance.toUpperCase() + " Table creation error", "SQLException", e.getMessage());
        } // try catch

        closeSQLObjects(con, stmt);

        LOGGER.debug(sqlInstance.toUpperCase() + " Trying to add '" + tableName + "' to the cache after table creation");
        cache.addTable(destination, tableName);
    } // createTable

    @Override
    public void insertContextData(String destination, String tableName, String fieldNames, String fieldValues)
            throws CygnusBadContextData, CygnusRuntimeError, CygnusPersistenceError {
        Statement stmt = null;

        // get a connection to the given destination
        Connection con = driver.getConnection(destination);
        String query = "";
        if (sqlInstance.equals("mysql")) {
            query = "insert into `" + tableName + "` " + fieldNames + " values " + fieldValues;
        } else {
            query = "INSERT INTO " + destination + "." + tableName + " " + fieldNames + " VALUES " + fieldValues;
        }

        try {
            stmt = con.createStatement();
        } catch (SQLException e) {
            closeSQLObjects(con, stmt);
            throw new CygnusRuntimeError(sqlInstance.toUpperCase() + " Data insertion error", "SQLException", e.getMessage());
        } // try catch

        try {
            LOGGER.debug(sqlInstance.toUpperCase() + " Executing SQL query '" + query + "'");
            stmt.executeUpdate(query);
        } catch (SQLTimeoutException e) {
            throw new CygnusPersistenceError(sqlInstance.toUpperCase() + " Data insertion error. Query insert into `" + tableName + "` " + fieldNames + " values " + fieldValues, "SQLTimeoutException", e.getMessage());
        } catch (SQLException e) {
            persistError(destination, query, e);
            throw new CygnusBadContextData(sqlInstance.toUpperCase() + " Data insertion error. Query: insert into `" + tableName + "` " + fieldNames + " values " + fieldValues, "SQLException", e.getMessage());
        } finally {
            closeSQLObjects(con, stmt);
        } // try catch

        LOGGER.debug(sqlInstance.toUpperCase() + " Trying to add '" + destination + "' and '" + tableName + "' to the cache after insertion");
        cache.addDestination(destination);
        cache.addTable(destination, tableName);
    } // insertContextData

    private CachedRowSet select(String destination, String tableName, String selection)
            throws CygnusRuntimeError, CygnusPersistenceError {
        Statement stmt = null;

        // get a connection to the given destination
        Connection con = driver.getConnection(destination);
        String query = "select " + selection + " from `" + tableName + "` order by recvTime";

        try {
            stmt = con.createStatement();
        } catch (SQLException e) {
            closeSQLObjects(con, stmt);
            throw new CygnusRuntimeError(sqlInstance.toUpperCase() + " Querying error", "SQLException", e.getMessage());
        } // try catch

        try {
            // to-do: refactor after implementing
            // https://github.com/telefonicaid/fiware-cygnus/issues/1371
            LOGGER.debug(sqlInstance.toUpperCase() + " Executing SQL query '" + query + "'");
            ResultSet rs = stmt.executeQuery(query);
            // A CachedRowSet is "disconnected" from the source, thus can be
            // used once the statement is closed
            @SuppressWarnings("restriction")
            CachedRowSet crs = new CachedRowSetImpl();

            crs.populate(rs); // FIXME: close Resultset Objects??
            closeSQLObjects(con, stmt);
            return crs;
        } catch (SQLTimeoutException e) {
            throw new CygnusPersistenceError(sqlInstance.toUpperCase() + " Data select error. Query " + query, "SQLTimeoutException", e.getMessage());
        } catch (SQLException e) {
            closeSQLObjects(con, stmt);
            persistError(destination, query, e);
            throw new CygnusPersistenceError(sqlInstance.toUpperCase() + " Querying error", "SQLException", e.getMessage());
        } // try catch
    } // select

    private void delete(String destination, String tableName, String filters)
            throws CygnusRuntimeError, CygnusPersistenceError {
        Statement stmt = null;

        // get a connection to the given destination
        Connection con = driver.getConnection(destination);
        String query = "delete from `" + tableName + "` where " + filters;

        try {
            stmt = con.createStatement();
        } catch (SQLException e) {
            closeSQLObjects(con, stmt);
            throw new CygnusRuntimeError(sqlInstance.toUpperCase() + " Deleting error", "SQLException", e.getMessage());
        } // try catch

        try {
            LOGGER.debug(sqlInstance.toUpperCase() + " Executing SQL query '" + query + "'");
            stmt.executeUpdate(query);
        } catch (SQLTimeoutException e) {
            throw new CygnusPersistenceError(sqlInstance.toUpperCase() + " Data delete error. Query " + query, "SQLTimeoutException", e.getMessage());
        }catch (SQLException e) {
            closeSQLObjects(con, stmt);
            persistError(destination, query, e);
            throw new CygnusPersistenceError(sqlInstance.toUpperCase() + " Deleting error", "SQLException", e.getMessage());
        } // try catch

        closeSQLObjects(con, stmt);
    } // delete

    @Override
    public void capRecords(String destination, String tableName, long maxRecords)
            throws CygnusRuntimeError, CygnusPersistenceError {
        // Get the records within the table
        CachedRowSet records = select(destination, tableName, "*");

        // Get the number of records
        int numRecords = 0;

        try {
            if (records.last()) {
                numRecords = records.getRow();
                records.beforeFirst();
            } // if
        } catch (SQLException e) {
            throw new CygnusRuntimeError(sqlInstance.toUpperCase() + " Data capping error", "SQLException", e.getMessage());
        } // try catch

        // Get the reception times (they work as IDs) for future deletion
        // to-do: refactor after implementing
        // https://github.com/telefonicaid/fiware-cygnus/issues/1371
        String filters = "";

        try {
            if (numRecords > maxRecords) {
                for (int i = 0; i < (numRecords - maxRecords); i++) {
                    records.next();
                    String recvTime = records.getString("recvTime");

                    if (filters.isEmpty()) {
                        filters += "recvTime='" + recvTime + "'";
                    } else {
                        filters += " or recvTime='" + recvTime + "'";
                    } // if else
                } // for
            } // if

            records.close();
        } catch (SQLException e) {
            throw new CygnusRuntimeError(sqlInstance.toUpperCase() + " Data capping error", "SQLException", e.getMessage());
        } // try catch

        if (filters.isEmpty()) {
            LOGGER.debug(sqlInstance.toUpperCase() + " No records to be deleted");
        } else {
            LOGGER.debug(sqlInstance.toUpperCase() + " Records must be deleted (destination=" + destination + ",tableName=" + tableName + ", filters="
                    + filters + ")");
            delete(destination, tableName, filters);
        } // if else
    } // capRecords

    @Override
    public void expirateRecordsCache(long expirationTime) throws CygnusRuntimeError, CygnusPersistenceError {
        // Iterate on the cached resource IDs
        cache.startDestinationIterator();

        while (cache.hasNextDestination()) {
            String destination = cache.nextDestination();
            cache.startTableIterator(destination);

            while (cache.hasNextTable(destination)) {
                String tableName = cache.nextTable(destination);

                // Get the records within the table
                CachedRowSet records = select(destination, tableName, "*");

                // Get the number of records
                int numRecords = 0;

                try {
                    if (records.last()) {
                        numRecords = records.getRow();
                        records.beforeFirst();
                    } // if
                } catch (SQLException e) {
                    try {
                        records.close();
                    } catch (SQLException e1) {
                        LOGGER.debug(sqlInstance.toUpperCase() + " Can't close CachedRowSet.");
                    }
                    throw new CygnusRuntimeError(sqlInstance.toUpperCase() + " Data expiration error", "SQLException", e.getMessage());
                } // try catch

                // Get the reception times (they work as IDs) for future
                // deletion
                // to-do: refactor after implementing
                // https://github.com/telefonicaid/fiware-cygnus/issues/1371
                String filters = "";

                try {
                    for (int i = 0; i < numRecords; i++) {
                        records.next();
                        String recvTime = records.getString("recvTime");
                        long recordTime = CommonUtils.getMilliseconds(recvTime);
                        long currentTime = new java.util.Date().getTime();

                        if (recordTime < (currentTime - (expirationTime * 1000))) {
                            if (filters.isEmpty()) {
                                filters += "recvTime='" + recvTime + "'";
                            } else {
                                filters += " or recvTime='" + recvTime + "'";
                            } // if else
                        } else {
                            break;
                        } // if else
                    } // for
                } catch (SQLException e) {
                    throw new CygnusRuntimeError(sqlInstance.toUpperCase() + " Data expiration error", "SQLException", e.getMessage());
                } catch (ParseException e) {
                    throw new CygnusRuntimeError(sqlInstance.toUpperCase() + " Data expiration error", "ParseException", e.getMessage());
                } // try catch

                if (filters.isEmpty()) {
                    LOGGER.debug(sqlInstance.toUpperCase() + " No records to be deleted");
                } else {
                    LOGGER.debug(sqlInstance.toUpperCase() + " Records must be deleted (destination=" + destination + ",tableName=" + tableName + ", filters="
                            + filters + ")");
                    delete(destination, tableName, filters);
                } // if else
            } // while
        } // while
    } // expirateRecordsCache

    /**
     * Close all the SQL objects previously opened by doCreateTable and
     * doQuery.
     *
     * @param con
     * @param stmt
     * @return True if the SQL objects have been closed, false otherwise.
     */
    private void closeSQLObjects(Connection con, Statement stmt) throws CygnusRuntimeError {
        LOGGER.debug(sqlInstance.toUpperCase() + " Closing SQL connection objects.");
        if (stmt != null) {
            try {
                stmt.close();
            } catch (SQLException e) {
                throw new CygnusRuntimeError(sqlInstance.toUpperCase() + " Objects closing error", "SQLException", e.getMessage());
            } // try catch
        } // if

        if (con != null) {
            try {
                con.close();
            } catch (SQLException e) {
                throw new CygnusRuntimeError(sqlInstance.toUpperCase() + " Objects closing error", "SQLException", e.getMessage());
            } // try catch
        } // if

    } // closeSQLObjects


    public void createErrorTable(String destination)
            throws CygnusRuntimeError, CygnusPersistenceError {
        // the defaul table for error log will be called the same as the destination name
        String errorTable = destination + "_error_log";
        if (cache.isCachedTable(destination, errorTable)) {
            LOGGER.debug(sqlInstance.toUpperCase() + " '" + errorTable + "' is cached, thus it is not created");
            return;
        } // if
        String typedFieldNames = "(" +
                "timestamp TIMESTAMP" +
                ", error text" +
                ", query text)";

        Statement stmt = null;
        // get a connection to the given destination
        Connection con = driver.getConnection(destination);

        String query = "";
        if (sqlInstance.equals("mysql")) {
            query = "create table if not exists `" + errorTable + "`" + typedFieldNames;
        } else {
            query = "create table if not exists " + destination + "." + errorTable + " " + typedFieldNames;
        }

        try {
            stmt = con.createStatement();
        } catch (SQLException e) {
            closeSQLObjects(con, stmt);
            throw new CygnusRuntimeError(sqlInstance.toUpperCase() + " Table creation error", "SQLException", e.getMessage());
        } // try catch

        try {
            LOGGER.debug(sqlInstance.toUpperCase() + " Executing SQL query '" + query + "'");
            stmt.executeUpdate(query);
        } catch (SQLException e) {
            closeSQLObjects(con, stmt);
            throw new CygnusPersistenceError(sqlInstance.toUpperCase() + " Table creation error", "SQLException", e.getMessage());
        } // try catch

        closeSQLObjects(con, stmt);

        LOGGER.debug(sqlInstance.toUpperCase() + " Trying to add '" + errorTable + "' to the cache after table creation");
        cache.addTable(destination, errorTable);
    } // createErrorTable

    private void insertErrorLog(String destination, String errorQuery, Exception exception)
            throws CygnusBadContextData, CygnusRuntimeError, CygnusPersistenceError, SQLException {
        Statement stmt = null;
        java.util.Date date = new Date();
        Timestamp timestamp = new Timestamp(date.getTime());
        String errorTable = destination + "_error_log";
        String fieldNames  = "(" +
                "timestamp" +
                ", error" +
                ", query)";

        // get a connection to the given destination
        Connection con = driver.getConnection(destination);

        String query = "";
        if (sqlInstance.equals("mysql")) {
            query = "insert into `" + errorTable + "` " + fieldNames + " values (?, ?, ?)";
        } else {
            query = "INSERT INTO " + destination + "." + errorTable + " " + fieldNames + " VALUES (?, ?, ?)";
        }

        PreparedStatement preparedStatement = con.prepareStatement(query);
        try {
            preparedStatement.setObject(1, java.sql.Timestamp.from(Instant.now()));
            preparedStatement.setString(2, exception.getMessage());
            preparedStatement.setString(3, errorQuery);
            LOGGER.debug(sqlInstance.toUpperCase() + " Executing SQL query '" + query + "'");
            preparedStatement.executeUpdate();
        } catch (SQLTimeoutException e) {
            throw new CygnusPersistenceError(sqlInstance.toUpperCase() + " Data insertion error. Query: `" + preparedStatement, "SQLTimeoutException", e.getMessage());
        } catch (SQLException e) {
            throw new CygnusBadContextData(sqlInstance.toUpperCase() + " Data insertion error. Query: `" + preparedStatement, "SQLException", e.getMessage());
        } finally {
            closeSQLObjects(con, preparedStatement);
        } // try catch

        LOGGER.debug(sqlInstance.toUpperCase() + " Trying to add '" + destination + "' and '" + errorTable + "' to the cache after insertion");
        cache.addDestination(destination);
        cache.addTable(destination, errorTable);
    } // insertErrorLog

    private void persistError(String destination, String query, Exception exception) throws CygnusPersistenceError, CygnusRuntimeError {
        try {
            createErrorTable(destination);
            insertErrorLog(destination, query, exception);
            return;
        } catch (CygnusBadContextData cygnusBadContextData) {
            LOGGER.debug(sqlInstance.toUpperCase() + " failed to persist error on database/scheme " + destination + "_error_log" + cygnusBadContextData);
            createErrorTable(destination);
        } catch (Exception e) {
            LOGGER.debug(sqlInstance.toUpperCase() + " failed to persist error on database/scheme " + destination + "_error_log" + e);
        }
    }

    public class SQLDriver {

        private final HashMap<String, DataSource> datasources;
        private final HashMap<String, GenericObjectPool> pools;
        private final String sqlHost;
        private final String sqlPort;
        private final String sqlUsername;
        private final String sqlPassword;
        private final String sqlInstance;
        private final String sqlDriverName;
        private final String sqlMode;
        private final String defaultSQLDataBase;
        private final int maxPoolSize;
        
        /**
         * Constructor.
         *
         * @param sqlHost
         * @param sqlPort
         * @param sqlUsername
         * @param sqlPassword
         * @param maxPoolSize
         * @param sqlInstance
         * @param sqlDriverName
         * @param sqlMode
         */
        public SQLDriver(String sqlHost, String sqlPort, String sqlUsername, String sqlPassword, int maxPoolSize, String sqlInstance, String sqlDriverName, String defaultSQLDataBase, String sqlMode) {
            datasources = new HashMap<>();
            pools = new HashMap<>();
            this.sqlHost = sqlHost;
            this.sqlPort = sqlPort;
            this.sqlUsername = sqlUsername;
            this.sqlPassword = sqlPassword;
            this.maxPoolSize = maxPoolSize;
            this.sqlInstance = sqlInstance;
            this.sqlDriverName = sqlDriverName;
            this.defaultSQLDataBase = defaultSQLDataBase;
            
            if (sqlMode == null){
                this.sqlMode = "";
            } else {
                this.sqlMode = sqlMode.trim();
            }
        } // SQLDriver

        /**
         * Constructor.
         * 
         * @param sqlHost
         * @param sqlPort
         * @param sqlUsername
         * @param sqlPassword
         * @param maxPoolSize
         * @param sqlInstance
         * @param sqlDriverName
         * @param defaultSQLDataBase
         */
        public SQLDriver(String sqlHost, String sqlPort, String sqlUsername, String sqlPassword, int maxPoolSize, String sqlInstance, String sqlDriverName, String defaultSQLDataBase) {
            this(sqlHost, sqlPort, sqlUsername, sqlPassword, maxPoolSize, sqlInstance, sqlDriverName, defaultSQLDataBase, "");
        }

        /**
         * Gets a connection to the SQL server.
         *
         * @param destination
         * @return
         * @throws CygnusRuntimeError
         * @throws CygnusPersistenceError
         */
        public Connection getConnection(String destination) throws CygnusRuntimeError, CygnusPersistenceError {
            try {
                // FIXME: the number of cached connections should be limited to
                // a certain number; with such a limit
                // number, if a new connection is needed, the oldest one is closed
                Connection connection = null;

                if (datasources.containsKey(destination)) {
                    connection = datasources.get(destination).getConnection();
                    LOGGER.debug(sqlInstance.toUpperCase() + " Recovered destination connection from cache (" + destination + ")");
                }

                if (connection == null || !connection.isValid(0)) {
                    if (connection != null) {
                        LOGGER.debug(sqlInstance.toUpperCase() + " Closing invalid sql connection for destination " + destination);
                        try {
                            connection.close();
                        } catch (SQLException e) {
                            LOGGER.warn(sqlInstance.toUpperCase() + " error closing invalid connection: " + e.getMessage());
                        }
                    } // if

                    DataSource datasource = createConnectionPool(destination);
                    datasources.put(destination, datasource);
                    connection = datasource.getConnection();
                } // if

                // Check Pool cache and log status
                if (pools.containsKey(destination)){
                    GenericObjectPool pool = pools.get(destination);
                    LOGGER.debug(sqlInstance.toUpperCase() + " Pool status (" + destination + ") Max.: " + pool.getMaxActive() + "; Active: "
                            + pool.getNumActive() + "; Idle: " + pool.getNumIdle());
                }else{
                    LOGGER.error(sqlInstance.toUpperCase() + " Can't find dabase in pool cache (" + destination + ")");
                }

                return connection;
            } catch (ClassNotFoundException e) {
                throw new CygnusRuntimeError(sqlInstance.toUpperCase() + " Connection error", "ClassNotFoundException", e.getMessage());
            } catch (SQLException e) {
                throw new CygnusPersistenceError(sqlInstance.toUpperCase() + " Connection error", "SQLException", e.getMessage());
            } catch (Exception e) {
                throw new CygnusRuntimeError(sqlInstance.toUpperCase() + " Connection error creating new Pool", "Exception", e.getMessage());
            } // try catch
        } // getConnection

        /**
         * Gets if a connection is created for the given destination. It is
         * protected since it is only used in the tests.
         *
         * @param destination
         * @return True if the connection exists, false other wise
         */
        protected boolean isConnectionCreated(String destination) {
            return datasources.containsKey(destination);
        } // isConnectionCreated

        /**
         * Returns the actual number of active connections
         * @return
         */
        protected int activePoolConnections() {
            int connectionCount = 0;
            for ( String destination : pools.keySet()){
                GenericObjectPool pool = pools.get(destination);
                connectionCount += pool.getNumActive();
                LOGGER.debug(sqlInstance.toUpperCase() + " Pool status (" + destination + ") Max.: " + pool.getMaxActive() + "; Active: "
                        + pool.getNumActive() + "; Idle: " + pool.getNumIdle());
            }
            LOGGER.debug(sqlInstance.toUpperCase() + " Total pool's active connections: " + connectionCount);
            return connectionCount;
        } // activePoolConnections

        /**
         * Returns the Maximum number of connections
         * @return
         */
        protected int maxPoolConnections() {
            int connectionCount = 0;
            for ( String destination : pools.keySet()){
                GenericObjectPool pool = pools.get(destination);
                connectionCount += pool.getMaxActive();
                LOGGER.debug(sqlInstance.toUpperCase() + " Pool status (" + destination + ") Max.: " + pool.getMaxActive() + "; Active: "
                        + pool.getNumActive() + "; Idle: " + pool.getNumIdle());
            }
            LOGGER.debug(sqlInstance.toUpperCase() + " Max pool connections: " + connectionCount);
            return connectionCount;
        } // maxPoolConnections

        /**
         * Gets the number of connections created.
         *
         * @return The number of connections created
         */
        protected int numConnectionsCreated() {
            return activePoolConnections();
        } // numConnectionsCreated

        /**
         * Create a connection pool for destination.
         *
         * @param destination
         * @return PoolingDataSource
         * @throws Exception
         */
        @SuppressWarnings("unused")
        private DataSource createConnectionPool(String destination) throws Exception {
            GenericObjectPool gPool = null;
            if (pools.containsKey(destination)){
                LOGGER.debug(sqlInstance.toUpperCase() + " Pool recovered from Cache (" + destination + ")");
                gPool = pools.get(destination);
            }else{
                String jdbcUrl = "";
                String variables = "";
                
                if (!"".equals(this.sqlMode)){
                    variables = "&sessionVariables=sql_mode='" + this.sqlMode + "'";
                }
                
                if (sqlInstance.equals("mysql")) {
                    jdbcUrl = "jdbc:" + sqlInstance + "://" + sqlHost + ":" + sqlPort + "/" + destination + variables;
                } else {
                    jdbcUrl = "jdbc:" + sqlInstance + "://" + sqlHost + ":" + sqlPort + "/" + defaultSQLDataBase + variables;
                }
                Class.forName(sqlDriverName);

                // Creates an Instance of GenericObjectPool That Holds Our Pool of Connections Object!
                gPool = new GenericObjectPool();
                gPool.setMaxActive(this.maxPoolSize);
                pools.put(destination, gPool);

                // Creates a ConnectionFactory Object Which Will Be Used by the Pool to Create the Connection Object!
                LOGGER.debug(sqlInstance.toUpperCase() + " Creating connection pool jdbc:" + sqlInstance +"://" + sqlHost + ":" + sqlPort + "/" + destination
                        + "?user=" + sqlUsername + "&password=XXXXXXXXXX");
                ConnectionFactory cf = new DriverManagerConnectionFactory(jdbcUrl, sqlUsername, sqlPassword);

                // Creates a PoolableConnectionFactory That Will Wraps the Connection Object Created by
                // the ConnectionFactory to Add Object Pooling Functionality!
                PoolableConnectionFactory pcf = new PoolableConnectionFactory(cf, gPool, null, null, false, true);
            } //else
            return new PoolingDataSource(gPool);
        } // createConnectionPool

        /**
         * Closes the Driver releasing resources
         * @return
         */
        public void close() {
            int poolCount = 0;
            int poolsSize = pools.size();

            for ( String destination : pools.keySet()){
                GenericObjectPool pool = pools.get(destination);
                try {
                    pool.close();
                    pools.remove(destination);
                    poolCount ++;
                    LOGGER.debug(sqlInstance.toUpperCase() + " Pool closed: (" + destination + ")");
                } catch (Exception e) {
                    LOGGER.error(sqlInstance.toUpperCase() + " Error closing SQL pool " + destination +": " + e.getMessage());
                }
            }
            LOGGER.debug(sqlInstance.toUpperCase() + " Number of Pools closed: " + poolCount + "/" + poolsSize);
        } // close

        /**
         * Last resort releasing resources
         */
        public void Finally(){
            this.close();
        }

    } // SQLDriver
}
