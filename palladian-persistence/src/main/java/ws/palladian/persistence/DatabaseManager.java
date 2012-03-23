package ws.palladian.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

/**
 * <p>
 * The {@link DatabaseManager} provides general database specific functionality. This implementation aims on wrapping
 * all ugly SQL specific details like {@link SQLException}s and automatically closes resources for you where applicable.
 * If you need to create your own application specific persistence layer, you may create your own subclass.
 * </p>
 * 
 * <p>
 * Instances of the DatabaseManager or its subclasses are created using the {@link DatabaseManagerFactory}, which takes
 * care of injecting the {@link DataSource}, which provides database connections.
 * </p>
 * 
 * @author David Urbansky
 * @author Philipp Katz
 * @author Klemens Muthmann
 */
public class DatabaseManager {

    /** The logger for this class. */
    private static final Logger LOGGER = Logger.getLogger(DatabaseManager.class);

    /**
     * The {@link DataSource} providing Connection to the underlying database.
     */
    private DataSource dataSource;

    /**
     * <p>
     * Creates a new {@code DatabaseManager} which connects to the database via the specified {@link DataSource}. The
     * constructor is not exposed since new objects of this type must be constructed using the
     * {@link DatabaseManagerFactory}.
     * </p>
     * 
     * @param dataSource
     */
    protected DatabaseManager(DataSource dataSource) {
        super();
        this.dataSource = dataSource;
    }

    /**
     * <p>
     * Get a {@link Connection} from the {@link DataSourceFactory}. If you use this method, e.g. in your subclass, it's
     * your responsibility to close all database resources after work has been done. This can be done conveniently by
     * using one of the various close methods offered by this class.
     * </p>
     * 
     * @return
     * @throws SQLException
     */
    protected final Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * <p>
     * Check, whether an item for the specified query exists.
     * </p>
     * 
     * @param sql Query statement which may contain parameter markers.
     * @param args (Optional) arguments for parameter markers in query.
     * @return <code>true</code> if at least on item exists, <code>false</code> otherwise.
     */
    public final boolean entryExists(String sql, List<Object> args) {
        return entryExists(sql, args.toArray());
    }

    /**
     * <p>
     * Check, whether an item for the specified query exists.
     * </p>
     * 
     * @param sql Query statement which may contain parameter markers.
     * @param args (Optional) arguments for parameter markers in query.
     * @return <code>true</code> if at least on item exists, <code>false</code> otherwise.
     */
    public final boolean entryExists(String sql, Object... args) {
        return runSingleQuery(new NopRowConverter(), sql, args) != null;
    }

    /**
     * <p>
     * Run a batch insertion. The generated ID for each inserted object is provided via the {@link BatchDataProvider}.
     * In case an error occurs, the whole batch is rolled back.
     * </p>
     * 
     * @param sql Update statement which may contain parameter markers.
     * @param provider A callback, which provides the necessary data for the insertion.
     * @return <code>true</code>, if batch insert was successful, <code>false</code> otherwise.
     */
    public final boolean runBatchInsert(String sql, BatchDataProvider provider) {

        Connection connection = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        boolean success = true;

        try {

            connection = getConnection();
            connection.setAutoCommit(false);
            ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);

            for (int i = 0; i < provider.getCount(); i++) {
                fillPreparedStatement(ps, provider.getData(i));
                ps.executeUpdate();

                rs = ps.getGeneratedKeys();
                if (rs.next()) {
                    provider.insertedItem(i, rs.getInt(1));
                } else {
                    success = false;
                    break;
                }

            }

            if (success) {
                connection.commit();
            } else {
                connection.rollback();
            }

            connection.setAutoCommit(true);

        } catch (SQLException e) {
            logError(e, sql);
        } finally {
            close(connection, ps, rs);
        }

        return success;
    }

    /**
     * <p>
     * Run a batch insertion and return the generated insert IDs.
     * </p>
     * 
     * @param sql Update statement which may contain parameter markers.
     * @param batchArgs List of arguments for the batch insertion. Arguments are supplied parameter lists.
     * @return Array with generated IDs for the data provided by the provider. This means, the size of the returned
     *         array reflects the number of batch insertions. If a specific row was not inserted, the array will contain
     *         a 0 value.
     */
    public final int[] runBatchInsertReturnIds(String sql, final List<List<Object>> batchArgs) {

        final int[] result = new int[batchArgs.size()];
        Arrays.fill(result, 0);

        BatchDataProvider provider = new BatchDataProvider() {

            @Override
            public int getCount() {
                return batchArgs.size();
            }

            @Override
            public List<Object> getData(int number) {
                List<Object> args = batchArgs.get(number);
                return args;
            }

            @Override
            public void insertedItem(int number, int generatedId) {
                result[number] = generatedId;
            }
        };

        runBatchInsert(sql, provider);
        return result;
    }

    public final int[] runBatchUpdate(String sql, BatchDataProvider provider) {

        Connection connection = null;
        PreparedStatement ps = null;
        int[] result = new int[0];

        try {

            connection = getConnection();
            connection.setAutoCommit(false);
            ps = connection.prepareStatement(sql);

            for (int i = 0; i < provider.getCount(); i++) {
                List<Object> args = provider.getData(i);
                fillPreparedStatement(ps, args);
                ps.addBatch();
            }

            result = ps.executeBatch();
            connection.commit();
            connection.setAutoCommit(true);

        } catch (SQLException e) {
            logError(e, sql);
        } finally {
            close(connection, ps);
        }

        return result;
    }

    public final int[] runBatchUpdate(String sql, final List<List<Object>> batchArgs) {

        BatchDataProvider provider = new BatchDataProvider() {

            @Override
            public int getCount() {
                return batchArgs.size();
            }

            @Override
            public List<Object> getData(int number) {
                List<Object> args = batchArgs.get(number);
                return args;
            }

            @Override
            public void insertedItem(int number, int generatedId) {
                // no op.
            }
        };

        return runBatchUpdate(sql, provider);
    }

    /**
     * <p>
     * Run a query which only returns a single {@link Integer} result (i.e. one row, one column). This is handy for
     * aggregate queries, like <code>COUNT</code>, <code>SUM</code>, <code>AVG</code>, <code>MAX</code>,
     * <code>MIN</code>. Example for such a query: <code>SELECT COUNT(*) FROM feeds WHERE id > 342</code>.
     * </p>
     * 
     * @param aggregateQuery The query string for the aggregated integer result.
     * @return The result of the query, or <code>null</code> if no result.
     */
    public final Integer runAggregateQuery(String aggregateQuery) {
        return runSingleQuery(new RowConverter<Integer>() {
            @Override
            public Integer convert(ResultSet resultSet) throws SQLException {
                return resultSet.getInt(1);
            }
        }, aggregateQuery);
    }

    /**
     * <p>
     * Run an insert operation and return the generated insert ID.
     * </p>
     * 
     * @param sql Update statement which may contain parameter markers.
     * @param args Arguments for parameter markers in updateStatement, if any.
     * @return The generated ID, or 0 if no id was generated, or -1 if an error occurred.
     */
    public final int runInsertReturnId(String sql, List<Object> args) {
        return runInsertReturnId(sql, args.toArray());
    }

    /**
     * <p>
     * Run an insert operation and return the generated insert ID.
     * </p>
     * 
     * @param sql Update statement which may contain parameter markers.
     * @param args Arguments for parameter markers in updateStatement, if any.
     * @return The generated ID, or 0 if no id was generated, or -1 if an error occurred.
     */
    public final int runInsertReturnId(String sql, Object... args) {

        int generatedId;
        Connection connection = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {

            connection = getConnection();
            ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            fillPreparedStatement(ps, args);
            ps.executeUpdate();

            rs = ps.getGeneratedKeys();
            if (rs.next()) {
                generatedId = rs.getInt(1);
            } else {
                generatedId = 0;
            }

        } catch (SQLException e) {
            logError(e, sql, args);
            generatedId = -1;
        } finally {
            close(connection, ps, rs);
        }

        return generatedId;
    }

    /**
     * 
     * @param query
     * @param entries
     * @param args
     * @return
     * @deprecated This should be done using {@link #runSingleQuery(RowConverter, String, Object...)} supplying a
     *             {@link RowConverter} returning an Object[]. There is no need to explicitly specify the number of
     *             entries.
     */
    @Deprecated
    public final Object[] runOneResultLineQuery(String query, final int entries, Object... args) {

        final Object[] resultEntries = new Object[entries];

        ResultSetCallback callback = new ResultSetCallback() {

            @Override
            public void processResult(ResultSet resultSet, int number) throws SQLException {
                for (int i = 1; i <= entries; i++) {
                    resultEntries[i - 1] = resultSet.getObject(i);
                }

            }
        };

        runQuery(callback, query, args);

        return resultEntries;
    }

    /**
     * <p>
     * Run a query operation on the database, process the result using a callback.
     * </p>
     * 
     * @param <T> Type of the processed objects.
     * @param callback The callback which is triggered for each result row of the query.
     * @param converter Converter for transforming the {@link ResultSet} to the desired type.
     * @param sql Query statement which may contain parameter markers.
     * @param args (Optional) arguments for parameter markers in query.
     * @return Number of processed results.
     */
    public final <T> int runQuery(ResultCallback<T> callback, RowConverter<T> converter, String sql, Object... args) {

        Connection connection = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        int counter = 0;

        try {

            connection = getConnection();
            ps = connection.prepareStatement(sql);
            fillPreparedStatement(ps, args);
            rs = ps.executeQuery();

            while (rs.next() && callback.isLooping()) {
                T item = converter.convert(rs);
                callback.processResult(item, ++counter);
            }

        } catch (SQLException e) {
            logError(e, sql, args);
        } finally {
            close(connection, ps, rs);
        }

        return counter;
    }

    /**
     * <p>
     * Run a query operation on the database, process the result using a callback.
     * </p>
     * 
     * @param callback The callback which is triggered for each result row of the query.
     * @param sql Query statement which may contain parameter markers.
     * @param args (Optional) arguments for parameter markers in query.
     * @return Number of processed results.
     */
    public final int runQuery(ResultSetCallback callback, String sql, Object... args) {
        return runQuery(callback, new NopRowConverter(), sql, args);
    }

    /**
     * <p>
     * Run a query operation on the database, return the result as List.
     * </p>
     * 
     * @param <T> Type of the processed objects.
     * @param converter Converter for transforming the {@link ResultSet} to the desired type.
     * @param sql Query statement which may contain parameter markers.
     * @param args (Optional) arguments for parameter markers in query.
     * @return List with results.
     */
    public final <T> List<T> runQuery(RowConverter<T> converter, String sql, List<Object> args) {
        return runQuery(converter, sql, args.toArray());
    }

    /**
     * <p>
     * Run a query operation on the database, return the result as List.
     * </p>
     * 
     * @param <T> Type of the processed objects.
     * @param converter Converter for transforming the {@link ResultSet} to the desired type.
     * @param sql Query statement which may contain parameter markers.
     * @param args (Optional) arguments for parameter markers in query.
     * @return List with results.
     */
    public final <T> List<T> runQuery(RowConverter<T> converter, String sql, Object... args) {

        final List<T> result = new ArrayList<T>();

        ResultCallback<T> callback = new ResultCallback<T>() {

            @Override
            public void processResult(T object, int number) {
                result.add(object);
            }

        };

        runQuery(callback, converter, sql, args);
        return result;
    }

    /**
     * <p>
     * Run a query operation on the database, return the result as Iterator. The underlying Iterator implementation does
     * not allow modifications, so invoking {@link ResultIterator#remove()} will cause an
     * {@link UnsupportedOperationException}. Database resources used by the implementation are closed, after the last
     * element has been retrieved. If you break the iteration loop, you <b>must</b> manually call
     * {@link ResultIterator#close()}. In general, you should prefer using
     * {@link #runQuery(ResultCallback, RowConverter, String, Object...)}, or
     * {@link #runQuery(ResultSetCallback, String, Object...)}, which will guarantee closing all database resources.
     * </p>
     * 
     * @param <T> Type of the processed objects.
     * @param converter Converter for transforming the {@link ResultSet} to the desired type.
     * @param sql Query statement which may contain parameter markers.
     * @param args (Optional) arguments for parameter markers in query.
     * @return Iterator for iterating over results.
     */
    public final <T> ResultIterator<T> runQueryWithIterator(RowConverter<T> converter, String sql, List<Object> args) {
        return runQueryWithIterator(converter, sql, args.toArray());
    }

    /**
     * <p>
     * Run a query operation on the database, return the result as Iterator. The underlying Iterator implementation does
     * not allow modifications, so invoking {@link ResultIterator#remove()} will cause an
     * {@link UnsupportedOperationException}. Database resources used by the implementation are closed, after the last
     * element has been retrieved. If you break the iteration loop, you <b>must</b> manually call
     * {@link ResultIterator#close()}. In general, you should prefer using
     * {@link #runQuery(ResultCallback, RowConverter, String, Object...)}, or
     * {@link #runQuery(ResultSetCallback, String, Object...)}, which will guarantee closing all database resources.
     * </p>
     * 
     * @param <T> Type of the processed objects.
     * @param converter Converter for transforming the {@link ResultSet} to the desired type.
     * @param sql Query statement which may contain parameter markers.
     * @param args (Optional) arguments for parameter markers in query.
     * @return Iterator for iterating over results.
     */
    public final <T> ResultIterator<T> runQueryWithIterator(RowConverter<T> converter, String sql, Object... args) {

        @SuppressWarnings("unchecked")
        ResultIterator<T> result = ResultIterator.NULL_ITERATOR;
        Connection connection = null;
        PreparedStatement ps = null;
        ResultSet resultSet = null;

        try {

            connection = getConnection();
            ps = connection.prepareStatement(sql);

            // do not buffer the whole ResultSet in memory, but use streaming to save memory
            // http://webmoli.com/2009/02/01/jdbc-performance-tuning-with-optimal-fetch-size/
            // TODO make this a global option?
            // ps.setFetchSize(Integer.MIN_VALUE);
            ps.setFetchSize(1);

            fillPreparedStatement(ps, args);

            resultSet = ps.executeQuery();
            result = new ResultIterator<T>(connection, ps, resultSet, converter);

        } catch (SQLException e) {
            logError(e, sql, args);
            close(connection, ps, resultSet);
        }

        return result;
    }

    /**
     * <p>
     * Run a query operation for a single item in the database.
     * </p>
     * 
     * @param <T> Type of the processed object.
     * @param converter Converter for transforming the {@link ResultSet} to the desired type.
     * @param sql Query statement which may contain parameter markers.
     * @param args (Optional) arguments for parameter markers in query.
     * @return The <i>first</i> retrieved item for the given query, or <code>null</code> no item found.
     */
    public final <T> T runSingleQuery(RowConverter<T> converter, String sql, List<Object> args) {
        return runSingleQuery(converter, sql, args.toArray());
    }

    /**
     * <p>
     * Run a query operation for a single item in the database.
     * </p>
     * 
     * @param <T> Type of the processed object.
     * @param converter Converter for transforming the {@link ResultSet} to the desired type.
     * @param sql Query statement which may contain parameter markers.
     * @param args (Optional) arguments for parameter markers in query.
     * @return The <i>first</i> retrieved item for the given query, or <code>null</code> no item found.
     */
    @SuppressWarnings("unchecked")
    public final <T> T runSingleQuery(RowConverter<T> converter, String sql, Object... args) {

        final Object[] result = new Object[1];

        ResultCallback<T> callback = new ResultCallback<T>() {

            @Override
            public void processResult(T object, int number) {
                result[0] = object;
                breakLoop();
            }
        };

        runQuery(callback, converter, sql, args);
        return (T)result[0];
    }

    /**
     * <p>
     * Run an update operation and return the number of affected rows.
     * </p>
     * 
     * @param sql Update statement which may contain parameter markers.
     * @param args Arguments for parameter markers in updateStatement, if any.
     * @return The number of affected rows, or -1 if an error occurred.
     */
    public final int runUpdate(String sql, List<Object> args) {
        return runUpdate(sql, args.toArray());
    }

    /**
     * <p>
     * Run an update operation and return the number of affected rows.
     * </p>
     * 
     * @param sql Update statement which may contain parameter markers.
     * @param args Arguments for parameter markers in updateStatement, if any.
     * @return The number of affected rows, or -1 if an error occurred.
     */
    public final int runUpdate(String sql, Object... args) {

        int affectedRows;
        Connection connection = null;
        PreparedStatement ps = null;

        try {

            connection = getConnection();
            ps = connection.prepareStatement(sql);
            fillPreparedStatement(ps, args);

            affectedRows = ps.executeUpdate();

        } catch (SQLException e) {
            logError(e, sql, args);
            affectedRows = -1;
        } finally {
            close(connection, ps);
        }

        return affectedRows;
    }

    // //////////////////////////////////////////////////////////////////////////////
    // Helper methods
    // //////////////////////////////////////////////////////////////////////////////

    /**
     * <p>
     * Log some diagnostics in case of errors. This includes the {@link SQLException} being thrown, the SQL statement
     * and the arguments, if any.
     * </p>
     * 
     * @param exception
     * @param sql
     * @param args The arguments for the SQL query, may be <code>null</code>.
     */
    protected static final void logError(SQLException exception, String sql, Object... args) {
        StringBuilder errorLog = new StringBuilder();
        errorLog.append("Exception " + exception.getMessage() + " when updating SQL \"" + sql + "\"");
        if (args != null && args.length > 0) {
            errorLog.append(" with args \"").append(StringUtils.join(args, ",")).append("\"");
        }
        LOGGER.error(errorLog.toString());
    }

    /**
     * <p>
     * Convenience method to close database resources. This method will perform <code>null</code> checking, close
     * resources where applicable and swallow all {@link SQLException}s.
     * </p>
     * 
     * @param connection
     */
    protected static final void close(Connection connection) {
        close(connection, null, null);
    }

    /**
     * <p>
     * Convenience method to close database resources. This method will perform <code>null</code> checking, close
     * resources where applicable and swallow all {@link SQLException}s.
     * </p>
     * 
     * @param connection
     * @param resultSet
     */
    protected static final void close(Connection connection, ResultSet resultSet) {
        close(connection, null, resultSet);
    }

    /**
     * <p>
     * Convenience method to close database resources. This method will perform <code>null</code> checking, close
     * resources where applicable and swallow all {@link SQLException}s.
     * </p>
     * 
     * @param connection
     * @param statement
     */
    protected static final void close(Connection connection, Statement statement) {
        close(connection, statement, null);
    }

    /**
     * <p>
     * Convenience method to close database resources. This method will perform <code>null</code> checking, close
     * resources where applicable and swallow all {@link SQLException}s.
     * </p>
     * 
     * @param connection
     * @param statement
     * @param resultSet
     */
    protected static final void close(Connection connection, Statement statement, ResultSet resultSet) {
        if (resultSet != null) {
            try {
                resultSet.close();
            } catch (SQLException e) {
                LOGGER.error("error closing ResultSet : " + e.getMessage());
            }
        }
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException e) {
                LOGGER.error("error closing Statement : " + e.getMessage());
            }
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                LOGGER.error("error closing Connection : " + e.getMessage());
            }
        }
    }

    /**
     * <p>
     * Sets {@link PreparedStatement} parameters based on the supplied arguments.
     * </p>
     * 
     * @param ps
     * @param args
     * @throws SQLException
     */
    protected static final void fillPreparedStatement(PreparedStatement ps, List<Object> args) throws SQLException {
        fillPreparedStatement(ps, args.toArray());
    }

    /**
     * <p>
     * Sets {@link PreparedStatement} parameters based on the supplied arguments.
     * </p>
     * 
     * @param ps
     * @param args
     * @throws SQLException
     */
    protected static final void fillPreparedStatement(PreparedStatement ps, Object... args) throws SQLException {

        // do we need a special treatment for NULL values here?
        // if you should stumble across this comment while debugging,
        // the answer is likely: yes, we do!
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }

}