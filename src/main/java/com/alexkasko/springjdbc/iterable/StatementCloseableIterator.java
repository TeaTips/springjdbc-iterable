package com.alexkasko.springjdbc.iterable;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.jdbc.InvalidResultSetAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.JdbcUtils;

import javax.sql.DataSource;
import java.sql.*;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Closable iterator implementation, returns mapped roes from provided result set.
 * Will be closed automatically on exhaustion.
 * Iterator implementation borrowed from guava's {@code AbstractIterator}.
 * NOT thread-safe.
 *
 * @author alexkasko
 * Date: 11/7/12
 */
class StatementCloseableIterator<T> implements CloseableIterator<T> {
    private static final Log logger = LogFactory.getLog(StatementCloseableIterator.class);

    private final DataSource ds;
    private final Connection conn;
    private final Statement stmt;
    private final ResultSet wrappedRs;
    private final ResultSet rsToUse;
    private final RowMapper<T> mapper;

    private enum State {READY, NOT_READY, DONE, FAILED}

    private State state = State.NOT_READY;
    private T next;
    private AtomicBoolean closed = new AtomicBoolean(false);
    private int rowNum = 0;

    /**
     * Constructor
     *
     * @param ds provided here for proper JDBC resources releasing
     * @param conn provided here for proper JDBC resources releasing
     * @param stmt provided here for proper JDBC resources releasing
     * @param wrappedRs provided here for proper JDBC resources releasing
     * @param rsToUse result set to iterate over
     * @param mapper row mapper to use
     */
    StatementCloseableIterator(DataSource ds, Connection conn, Statement stmt, ResultSet wrappedRs, ResultSet rsToUse, RowMapper<T> mapper) {
        this.ds = ds;
        this.conn = conn;
        this.stmt = stmt;
        this.wrappedRs = wrappedRs;
        this.rsToUse = rsToUse;
        this.mapper = mapper;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final T next() {
        if(!hasNext()) throw new NoSuchElementException();
        state = State.NOT_READY;
        return next;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean hasNext() {
        if(state == State.FAILED) throw new IllegalStateException();
        switch(state) {
            case DONE:
                return false;
            case READY:
                return true;
            default:
        }
        return tryToComputeNext();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void remove() {
        throw new UnsupportedOperationException("remove");
    }

    /**
     * Releases JDBC resources exactly the same way {@code JdbcTemplate} does.
     * See finally clause {@code JdbcTemplate#query(org.springframework.jdbc.core.PreparedStatementCreator, org.springframework.jdbc.core.PreparedStatementSetter, org.springframework.jdbc.core.ResultSetExtractor)}
     * See finally clause {@code JdbcTemplate#execute(org.springframework.jdbc.core.PreparedStatementCreator, org.springframework.jdbc.core.PreparedStatementCallback)}
     */
    @Override
    public void close() {
        if(!closed.compareAndSet(false, true)) return;
        JdbcUtils.closeResultSet(wrappedRs);
        JdbcUtils.closeStatement(stmt);
        DataSourceUtils.releaseConnection(conn, ds);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        if(!closed.get()) logger.warn("GC level message: iterator wasn't closed: [" + this + "]");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("ResultSetCloseableIterator");
        sb.append("{ds=").append(ds);
        sb.append(", conn=").append(conn);
        sb.append(", stmt=").append(stmt);
        sb.append(", wrappedRs=").append(wrappedRs);
        sb.append(", rsToUse=").append(rsToUse);
        sb.append(", mapper=").append(mapper);
        sb.append(", closed=").append(closed);
        sb.append(", rowNum=").append(rowNum);
        sb.append('}');
        return sb.toString();
    }

    /**
     *  Wrapper method for exceptions catching
     *
     * @return next iter value or {@code endOfData()}
     * @throws org.springframework.jdbc.InvalidResultSetAccessException on result set access problem
     */
    private T computeNext() {
        try {
            return computeNextInternal();
        } catch(SQLException e) {
            throw new InvalidResultSetAccessException(e);
        }
    }

    /**
     * Iterator logic is here. Closes iterator on exhausted result set
     *
     * @return next iter value or {@code endOfData()}
     * @throws java.sql.SQLException on result set access problem
     */
    private T computeNextInternal() throws SQLException {
        if(closed.get()) return endOfData();
        if(rsToUse.next()) return mapper.mapRow(rsToUse, rowNum++);
        close();
        return endOfData();
    }

    /**
     * borrowed from guava's {@code AbstractIterator}
     */
    private T endOfData() {
        state = State.DONE;
        return null;
    }

    /**
     * borrowed from guava's {@code AbstractIterator}
     */
    private boolean tryToComputeNext() {
        state = State.FAILED; // temporary pessimism
        next = computeNext();
        if(state != State.DONE) {
            state = State.READY;
            return true;
        }
        return false;
    }
}
