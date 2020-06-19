/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2020 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ext.clickhouse.model;

import java.sql.SQLException;
import java.util.Date;
import java.util.Map;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.generic.model.GenericStructContainer;
import org.jkiss.dbeaver.ext.generic.model.GenericTable;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBPObjectStatistics;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDDataReceiver;
import org.jkiss.dbeaver.model.data.DBDValueHandler;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionSource;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.data.ExecuteBatchImpl;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.preferences.DBPPropertySource;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSAttributeBase;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.ByteNumberFormat;

/**
 * ClickhouseTable
 */
public class ClickhouseTable extends GenericTable implements DBPObjectStatistics
{
    private static final Log log = Log.getLog(ClickhouseTable.class);

    private Long tableSize;
    private long tableRows;
    private Date lastModifyTime;
    private String maxDate;
    private String minDate;
    private String engine;

    ClickhouseTable(GenericStructContainer container, @Nullable String tableName, @Nullable String tableType, @Nullable JDBCResultSet dbResult) {
        super(container, tableName, tableType, dbResult);
    }

    @Override
    public boolean hasStatistics() {
        return tableSize != null;
    }

    @Property(category = CAT_STATISTICS, viewable = true, order = 20, formatter = ByteNumberFormat.class)
    @Override
    public long getStatObjectSize() {
        return tableSize == null ? 0 : tableSize;
    }

    @Property(category = CAT_STATISTICS, viewable = true, order = 21)
    @Nullable
    @Override
    public synchronized Long getRowCount(DBRProgressMonitor monitor) {
        readStatistics(monitor);
        return tableRows;
    }

    @Property(category = CAT_STATISTICS, order = 22)
    public Date getLastModifyTime(DBRProgressMonitor monitor) {
        readStatistics(monitor);
        return lastModifyTime;
    }

    @Property(category = CAT_STATISTICS, order = 23)
    public String getMinDate(DBRProgressMonitor monitor) {
        readStatistics(monitor);
        return minDate;
    }

    @Property(category = CAT_STATISTICS, order = 24)
    public String getMaxDate(DBRProgressMonitor monitor) {
        readStatistics(monitor);
        return maxDate;
    }

    @Property(category = CAT_STATISTICS, viewable = true, order = 25)
    public String getEngine(DBRProgressMonitor monitor) {
        readStatistics(monitor);
        return engine;
    }

    @Nullable
    @Override
    public DBPPropertySource getStatProperties() {
        return null;
    }

    private void readStatistics(DBRProgressMonitor monitor) {
        if (hasStatistics()) {
            return;
        }
        try (DBCSession session = DBUtils.openMetaSession(monitor, this, "Read relation statistics")) {
            try (JDBCPreparedStatement dbStat = ((JDBCSession)session).prepareStatement(
                "select " +
                    "sum(bytes) as table_size, " +
                    "sum(rows) as table_rows, " +
                    "max(modification_time) as latest_modification," +
                    "min(min_date) AS min_date," +
                    "max(max_date) AS max_date," +
                    "any(engine) as engine\n" +
                    "FROM system.parts\n" +
                    "WHERE database=? AND table=?\n" +
                    "GROUP BY table"))
            {
                dbStat.setString(1, getContainer().getName());
                dbStat.setString(2, getName());
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    if (dbResult.next()) {
                        fetchStatistics(dbResult);
                    }
                }
            } catch (SQLException e) {
                log.error("Error reading table statistics", e);
            }
        }
    }

    void fetchStatistics(JDBCResultSet dbResult) throws SQLException {
        tableSize = JDBCUtils.safeGetLong(dbResult, "table_size");
        tableRows = JDBCUtils.safeGetLong(dbResult, "table_rows");
        lastModifyTime = JDBCUtils.safeGetTimestamp(dbResult, "latest_modification");
        maxDate = JDBCUtils.safeGetString(dbResult, "max_date");
        minDate = JDBCUtils.safeGetString(dbResult, "min_date");
        engine = JDBCUtils.safeGetString(dbResult, "engine");
    }

    @Override
    public ExecuteBatch updateData(@NotNull DBCSession session, @NotNull DBSAttributeBase[] updateAttributes,
            @NotNull DBSAttributeBase[] keyAttributes, @Nullable DBDDataReceiver keysReceiver,
            @NotNull DBCExecutionSource source) throws DBCException {
        final DBSAttributeBase[] attributes = ArrayUtils.concatArrays(updateAttributes, keyAttributes);
        return new ExecuteBatchImpl(attributes, keysReceiver, false) {

            @NotNull
            @Override
            protected DBCStatement prepareStatement(@NotNull DBCSession session, DBDValueHandler[] handlers,
                    Object[] attributeValues, Map<String, Object> options) throws DBCException {

                final StringBuilder sql = new StringBuilder();

                // ALTER
                sql.append("ALTER TABLE").append(" ");
                sql.append(DBUtils.getEntityScriptName(ClickhouseTable.this, options)).append(" ");

                // UPDATE
                sql.append("UPDATE").append(" ");
                boolean hasAttribute = false;
                for (DBSAttributeBase updateAttribute : updateAttributes) {
                    if (!DBUtils.isPseudoAttribute(updateAttribute) && !DBUtils.isHiddenObject(updateAttribute)) {
                        final String typeName = DBUtils.getFullTypeName(updateAttribute);
                        final String key = DBUtils.getObjectFullName(updateAttribute, DBPEvaluationContext.DML);
                        final String value = valuePlaceholder(typeName);
                        if (hasAttribute) {
                            sql.append(", ");
                        }
                        sql.append(key).append("=").append(value).append(" ");
                        hasAttribute = true;
                    }
                }

                // WHERE
                sql.append("WHERE").append(" ");
                boolean hasKeyAttribute = false;
                for (DBSAttributeBase keyAttribute : keyAttributes) {
                    final String typeName = DBUtils.getFullTypeName(keyAttribute);
                    final String key = DBUtils.getObjectFullName(keyAttribute, DBPEvaluationContext.DML);
                    final String operator = DBUtils.isNullValue(keyAttribute) ? " IS NULL " : " = ";
                    final String value = valuePlaceholder(typeName);
                    if (hasKeyAttribute) {
                        sql.append(" AND ");
                    }
                    sql.append(key).append(operator).append(value);
                    hasKeyAttribute = true;
                }

                sql.append(";\n");

                // Execute
                final DBCStatement statement = session.prepareStatement(DBCStatementType.QUERY, sql.toString(), false,
                        false, keysReceiver != null);
                statement.setStatementSource(source);
                return statement;
            }

            @Override
            protected void bindStatement(@NotNull DBDValueHandler[] handlers, @NotNull DBCStatement statement,
                    Object[] attributeValues) throws DBCException {
                for (int i = 0; i < handlers.length; i++) {
                    handlers[i].bindValueObject(statement.getSession(), statement, attributes[i], i,
                            attributeValues[i]);
                }
            }
        };
    }

    @Override
    public ExecuteBatch deleteData(DBCSession session, DBSAttributeBase[] keyAttributes, DBCExecutionSource source)
            throws DBCException {
        return new ExecuteBatchImpl(keyAttributes, null, false) {

            @NotNull
            @Override
            protected DBCStatement prepareStatement(@NotNull DBCSession session, DBDValueHandler[] handlers,
                    Object[] attributeValues, Map<String, Object> options) throws DBCException {

                final StringBuilder sql = new StringBuilder();

                // ALTER
                sql.append("ALTER TABLE").append(" ");
                sql.append(DBUtils.getEntityScriptName(ClickhouseTable.this, options)).append(" ");

                // DELETE
                sql.append("DELETE").append(" ");

                // WHERE
                sql.append("WHERE").append(" ");
                boolean hasKeyAttribute = false;
                for (DBSAttributeBase keyAttribute : keyAttributes) {
                    final String typeName = DBUtils.getFullTypeName(keyAttribute);
                    final String key = DBUtils.getObjectFullName(keyAttribute, DBPEvaluationContext.DML);
                    final String operator = DBUtils.isNullValue(keyAttribute) ? " IS NULL " : " = ";
                    final String value = valuePlaceholder(typeName);
                    if (hasKeyAttribute) {
                        sql.append(" AND ");
                    }
                    sql.append(key).append(operator).append(value);
                    hasKeyAttribute = true;
                }

                sql.append(";\n");

                // Execute
                final DBCStatement statement = session.prepareStatement(DBCStatementType.QUERY, sql.toString(), false,
                        false, keysReceiver != null);
                statement.setStatementSource(source);
                return statement;
            }

            @Override
            protected void bindStatement(@NotNull DBDValueHandler[] handlers, @NotNull DBCStatement statement,
                    Object[] attributeValues) throws DBCException {
                for (int i = 0; i < handlers.length; i++) {
                    handlers[i].bindValueObject(statement.getSession(), statement, attributes[i], i,
                            attributeValues[i]);
                }
            }
        };
    }

    private String valuePlaceholder(String typeName) {
        if (typeName != null && typeName.toUpperCase().startsWith("ENUM")) {
            return "CAST(?, '" + typeName.replace("'", "\\'") + "')";
        } else if (typeName != null && typeName.equalsIgnoreCase("UUID")) {
            return "toUUID(?)";
        } else if (typeName != null && typeName.equalsIgnoreCase("Date")) {
            return "toDate(?)";
        } else if (typeName != null && typeName.equalsIgnoreCase("DateTime")) {
            return "toDateTime(?)";
        } else {
            return "?";
        }
    }

}
