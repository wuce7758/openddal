/*
 * Copyright 2014-2016 the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the “License”);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an “AS IS” BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.openddal.result;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;

import com.openddal.command.expression.Expression;
import com.openddal.engine.Session;
import com.openddal.message.DbException;
import com.openddal.util.New;
import com.openddal.util.ValueHashMap;
import com.openddal.value.DataType;
import com.openddal.value.Value;
import com.openddal.value.ValueArray;

/**
 * A local result set contains all row data of a result set. This is the object
 * generated by engine, and it is also used directly by the ResultSet class in
 * the embedded mode. If the result does not fit in memory, it is written to a
 * temporary file.
 */
public class LocalResult implements ResultInterface, ResultTarget {

    private int maxMemoryRows;
    private Session session;
    private int visibleColumnCount;
    private Expression[] expressions;
    private int rowId, rowCount;
    private ArrayList<Value[]> rows;
    private SortOrder sort;
    private ValueHashMap<Value[]> distinctRows;
    private Value[] currentRow;
    private int offset;
    private int limit = -1;
    private boolean distinct;
    private boolean randomAccess;
    private boolean closed;
    /**
     * Construct a local result object.
     */
    public LocalResult() {
        // nothing to do
    }

    /**
     * Construct a local result object.
     *
     * @param session the session
     * @param expressions the expression array
     * @param visibleColumnCount the number of visible columns
     */
    public LocalResult(Session session, Expression[] expressions, int visibleColumnCount) {
        this.session = session;
        if (session == null) {
            this.maxMemoryRows = Integer.MAX_VALUE;
        } else {
            this.maxMemoryRows = session.getDatabase().getMaxMemoryRows();
        }
        rows = New.arrayList();
        this.visibleColumnCount = visibleColumnCount;
        rowId = -1;
        this.expressions = expressions;
    }

    public void setMaxMemoryRows(int maxValue) {
        this.maxMemoryRows = maxValue;
    }

    /**
     * Construct a local result set by reading all data from a regular result
     * set.
     *
     * @param session the session
     * @param rs the result set
     * @param maxrows the maximum number of rows to read (0 for no limit)
     * @return the local result set
     */
    public static LocalResult read(Session session, ResultSet rs, int maxrows) {
        Expression[] cols = Expression.getExpressionColumns(session, rs);
        int columnCount = cols.length;
        LocalResult result = new LocalResult(session, cols, columnCount);
        try {
            for (int i = 0; (maxrows == 0 || i < maxrows) && rs.next(); i++) {
                Value[] list = new Value[columnCount];
                for (int j = 0; j < columnCount; j++) {
                    int type = result.getColumnType(j);
                    list[j] = DataType.readValue(session, rs, j + 1, type);
                }
                result.addRow(list);
            }
        } catch (SQLException e) {
            throw DbException.convert(e);
        }
        result.done();
        return result;
    }
    
    /**
     * Create a shallow copy of the result set. The data and a temporary table
     * (if there is any) is not copied.
     *
     * @param targetSession the session of the copy
     * @return the copy if possible, or null if copying is not possible
     */
    public LocalResult createShallowCopy(Session targetSession) {
        if (rows == null || rows.size() < rowCount) {
            return null;
        }
        LocalResult copy = new LocalResult();
        copy.maxMemoryRows = this.maxMemoryRows;
        copy.session = targetSession;
        copy.visibleColumnCount = this.visibleColumnCount;
        copy.expressions = this.expressions;
        copy.rowId = -1;
        copy.rowCount = this.rowCount;
        copy.rows = this.rows;
        copy.sort = this.sort;
        copy.distinctRows = this.distinctRows;
        copy.distinct = distinct;
        copy.randomAccess = randomAccess;
        copy.currentRow = null;
        copy.offset = 0;
        copy.limit = -1;
        return copy;
    }

    /**
     * Set the sort order.
     *
     * @param sort the sort order
     */
    public void setSortOrder(SortOrder sort) {
        this.sort = sort;
    }

    /**
     * Remove duplicate rows.
     */
    public void setDistinct() {
        distinct = true;
        distinctRows = ValueHashMap.newInstance();
    }

    /**
     * Random access is required (containsDistinct).
     */
    public void setRandomAccess() {
        this.randomAccess = true;
    }

    /**
     * Remove the row from the result set if it exists.
     *
     * @param values the row
     */
    public void removeDistinct(Value[] values) {
        if (!distinct) {
            DbException.throwInternalError();
        }
        ValueArray array = ValueArray.get(values);
        distinctRows.remove(array);
        rowCount = distinctRows.size();
    
    }

    /**
     * Check if this result set contains the given row.
     *
     * @param values the row
     * @return true if the row exists
     */
    public boolean containsDistinct(Value[] values) {
        if (distinctRows == null) {
            distinctRows = ValueHashMap.newInstance();
            for (Value[] row : rows) {
                ValueArray array = getArrayOfVisible(row);
                distinctRows.put(array, array.getList());
            }
        }
        ValueArray array = ValueArray.get(values);
        return distinctRows.get(array) != null;
    }

    @Override
    public void reset() {
        rowId = -1;
    }

    @Override
    public Value[] currentRow() {
        return currentRow;
    }

    @Override
    public boolean next() {
        if (!closed && rowId < rowCount) {
            rowId++;
            if (rowId < rowCount) {
                currentRow = rows.get(rowId);
                return true;
            }
            currentRow = null;
        }
        return false;
    }

    @Override
    public int getRowId() {
        return rowId;
    }

    private void cloneLobs(Value[] values) {
        for (int i = 0; i < values.length; i++) {
            Value v = values[i];
            Value v2 = v.copyToResult();
            if (v2 != v) {
                session.addTemporaryLob(v2);
                values[i] = v2;
            }
        }
    }

    private ValueArray getArrayOfVisible(Value[] values) {
        if (values.length > visibleColumnCount) {
            Value[] v2 = new Value[visibleColumnCount];
            System.arraycopy(values, 0, v2, 0, visibleColumnCount);
            values = v2;
        }
        return ValueArray.get(values);
    }

    /**
     * Add a row to this object.
     *
     * @param values the row to add
     */
    @Override
    public void addRow(Value[] values) {
        cloneLobs(values);
        if (distinct) {
            ValueArray array = getArrayOfVisible(values);
            distinctRows.put(array, values);
            rowCount = distinctRows.size();
            if (rowCount > maxMemoryRows) {
                throw DbException.getUnsupportedException("too big result row " + maxMemoryRows);
            }
            return;
        }
        rows.add(values);
        rowCount++;
        if (rows.size() > maxMemoryRows) {
            throw DbException.getUnsupportedException("too big result row " + maxMemoryRows);
        }
    }

    @Override
    public int getVisibleColumnCount() {
        return visibleColumnCount;
    }

    /**
     * This method is called after all rows have been added.
     */
    public void done() {
        if (distinct) {
            rows = distinctRows.values();
        }
        if (sort != null) {
            if (offset > 0 || limit > 0) {
                sort.sort(rows, offset, limit < 0 ? rows.size() : limit);
            } else {
                sort.sort(rows);
            }
        }
        
        applyOffset();
        applyLimit();
        reset();
    }

    @Override
    public int getRowCount() {
        return rowCount;
    }

    /**
     * Set the number of rows that this result will return at the maximum.
     *
     * @param limit the limit (-1 means no limit, 0 means no rows)
     */
    public void setLimit(int limit) {
        this.limit = limit;
    }

    private void applyLimit() {
        if (limit < 0) {
            return;
        }
        if (rows.size() > limit) {
            rows = New.arrayList(rows.subList(0, limit));
            rowCount = limit;
        }
    }

    @Override
    public boolean needToClose() {
        return false;
    }

    @Override
    public void close() {
        
    }

    @Override
    public String getAlias(int i) {
        return expressions[i].getAlias();
    }

    @Override
    public String getTableName(int i) {
        return expressions[i].getTableName();
    }

    @Override
    public String getSchemaName(int i) {
        return expressions[i].getSchemaName();
    }

    @Override
    public int getDisplaySize(int i) {
        return expressions[i].getDisplaySize();
    }

    @Override
    public String getColumnName(int i) {
        return expressions[i].getColumnName();
    }

    @Override
    public int getColumnType(int i) {
        return expressions[i].getType();
    }

    @Override
    public long getColumnPrecision(int i) {
        return expressions[i].getPrecision();
    }

    @Override
    public int getNullable(int i) {
        return expressions[i].getNullable();
    }

    @Override
    public boolean isAutoIncrement(int i) {
        return expressions[i].isAutoIncrement();
    }

    @Override
    public int getColumnScale(int i) {
        return expressions[i].getScale();
    }

    /**
     * Set the offset of the first row to return.
     *
     * @param offset the offset
     */
    public void setOffset(int offset) {
        this.offset = offset;
    }

    private void applyOffset() {
        if (offset <= 0) {
            return;
        }

        if (offset >= rows.size()) {
            rows.clear();
            rowCount = 0;
        } else {
            // avoid copying the whole array for each row
            int remove = Math.min(offset, rows.size());
            rows = New.arrayList(rows.subList(remove, rows.size()));
            rowCount -= remove;
        }
    }

    @Override
    public String toString() {
        return super.toString() + " columns: " + visibleColumnCount + " rows: " + rowCount + " pos: " + rowId;
    }

    /**
     * Check if this result set is closed.
     *
     * @return true if it is
     */
    public boolean isClosed() {
        return closed;
    }

    @Override
    public int getFetchSize() {
        return 0;
    }

    @Override
    public void setFetchSize(int fetchSize) {
        // ignore
    }

}
