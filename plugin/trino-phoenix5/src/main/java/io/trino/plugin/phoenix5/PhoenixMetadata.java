/*
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
package io.trino.plugin.phoenix5;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import io.airlift.slice.Slice;
import io.trino.plugin.base.mapping.IdentifierMapping;
import io.trino.plugin.jdbc.DefaultJdbcMetadata;
import io.trino.plugin.jdbc.JdbcColumnHandle;
import io.trino.plugin.jdbc.JdbcNamedRelationHandle;
import io.trino.plugin.jdbc.JdbcQueryEventListener;
import io.trino.plugin.jdbc.JdbcTableHandle;
import io.trino.plugin.jdbc.JdbcTypeHandle;
import io.trino.plugin.jdbc.RemoteTableName;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.AggregateFunction;
import io.trino.spi.connector.AggregationApplicationResult;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorInsertTableHandle;
import io.trino.spi.connector.ConnectorMergeTableHandle;
import io.trino.spi.connector.ConnectorOutputMetadata;
import io.trino.spi.connector.ConnectorOutputTableHandle;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTableLayout;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.ConnectorTableProperties;
import io.trino.spi.connector.ConnectorTableSchema;
import io.trino.spi.connector.LocalProperty;
import io.trino.spi.connector.RetryMode;
import io.trino.spi.connector.RowChangeParadigm;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.SortingProperty;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.security.TrinoPrincipal;
import io.trino.spi.statistics.ComputedStatistics;
import io.trino.spi.type.RowType;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.plugin.phoenix5.MetadataUtil.getEscapedTableName;
import static io.trino.plugin.phoenix5.MetadataUtil.toTrinoSchemaName;
import static io.trino.plugin.phoenix5.PhoenixClient.MERGE_ROW_ID_COLUMN_NAME;
import static io.trino.plugin.phoenix5.PhoenixErrorCode.PHOENIX_METADATA_ERROR;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.connector.RetryMode.NO_RETRIES;
import static io.trino.spi.connector.RowChangeParadigm.CHANGE_ONLY_UPDATED_COLUMNS;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.apache.phoenix.util.SchemaUtil.getEscapedArgument;

public class PhoenixMetadata
        extends DefaultJdbcMetadata
{
    // Maps to Phoenix's default empty schema
    public static final String DEFAULT_SCHEMA = "default";
    // col name used for PK if none provided in DDL
    private static final String ROWKEY = "ROWKEY";

    private final PhoenixClient phoenixClient;
    private final IdentifierMapping identifierMapping;

    @Inject
    public PhoenixMetadata(PhoenixClient phoenixClient, IdentifierMapping identifierMapping, Set<JdbcQueryEventListener> jdbcQueryEventListeners)
    {
        super(phoenixClient, false, jdbcQueryEventListeners);
        this.phoenixClient = requireNonNull(phoenixClient, "phoenixClient is null");
        this.identifierMapping = requireNonNull(identifierMapping, "identifierMapping is null");
    }

    @Override
    public JdbcTableHandle getTableHandle(ConnectorSession session, SchemaTableName schemaTableName)
    {
        return phoenixClient.getTableHandle(session, schemaTableName)
                .map(JdbcTableHandle::asPlainTable)
                .map(JdbcNamedRelationHandle::getRemoteTableName)
                .map(remoteTableName -> new JdbcTableHandle(
                        schemaTableName,
                        new RemoteTableName(remoteTableName.getCatalogName(), Optional.ofNullable(toTrinoSchemaName(remoteTableName.getSchemaName().orElse(null))), remoteTableName.getTableName()),
                        Optional.empty()))
                .orElse(null);
    }

    @Override
    public ConnectorTableProperties getTableProperties(ConnectorSession session, ConnectorTableHandle table)
    {
        JdbcTableHandle tableHandle = (JdbcTableHandle) table;
        List<LocalProperty<ColumnHandle>> sortingProperties = tableHandle.getSortOrder()
                .map(properties -> properties
                        .stream()
                        .map(item -> (LocalProperty<ColumnHandle>) new SortingProperty<ColumnHandle>(
                                item.getColumn(),
                                item.getSortOrder()))
                        .collect(toImmutableList()))
                .orElse(ImmutableList.of());

        return new ConnectorTableProperties(TupleDomain.all(), Optional.empty(), Optional.empty(), sortingProperties);
    }

    @Override
    public ConnectorTableSchema getTableSchema(ConnectorSession session, ConnectorTableHandle table)
    {
        JdbcTableHandle handle = (JdbcTableHandle) table;
        return new ConnectorTableSchema(
                handle.getRequiredNamedRelation().getSchemaTableName(),
                getColumnMetadata(session, handle).stream()
                        .map(ColumnMetadata::getColumnSchema)
                        .collect(toImmutableList()));
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle table)
    {
        JdbcTableHandle handle = (JdbcTableHandle) table;
        return new ConnectorTableMetadata(
                handle.getRequiredNamedRelation().getSchemaTableName(),
                getColumnMetadata(session, handle),
                phoenixClient.getTableProperties(session, handle));
    }

    private List<ColumnMetadata> getColumnMetadata(ConnectorSession session, JdbcTableHandle handle)
    {
        return phoenixClient.getColumns(session, handle).stream()
                .filter(column -> !ROWKEY.equalsIgnoreCase(column.getColumnName()))
                .map(JdbcColumnHandle::getColumnMetadata)
                .collect(toImmutableList());
    }

    @Override
    public void createSchema(ConnectorSession session, String schemaName, Map<String, Object> properties, TrinoPrincipal owner)
    {
        checkArgument(properties.isEmpty(), "Can't have properties for schema creation");
        if (DEFAULT_SCHEMA.equalsIgnoreCase(schemaName)) {
            throw new TrinoException(NOT_SUPPORTED, "Can't create 'default' schema which maps to Phoenix empty schema");
        }
        phoenixClient.execute(session, format("CREATE SCHEMA %s", getEscapedArgument(toRemoteSchemaName(session, schemaName))));
    }

    @Override
    public void dropSchema(ConnectorSession session, String schemaName, boolean cascade)
    {
        if (cascade) {
            // Phoenix doesn't support CASCADE option https://phoenix.apache.org/language/index.html#drop_schema
            throw new TrinoException(NOT_SUPPORTED, "This connector does not support dropping schemas with CASCADE option");
        }
        if (DEFAULT_SCHEMA.equalsIgnoreCase(schemaName)) {
            throw new TrinoException(NOT_SUPPORTED, "Can't drop 'default' schema which maps to Phoenix empty schema");
        }
        phoenixClient.execute(session, format("DROP SCHEMA %s", getEscapedArgument(toRemoteSchemaName(session, schemaName))));
    }

    private String toRemoteSchemaName(ConnectorSession session, String schemaName)
    {
        try (Connection connection = phoenixClient.getConnection(session)) {
            return identifierMapping.toRemoteSchemaName(phoenixClient.getRemoteIdentifiers(connection), session.getIdentity(), schemaName);
        }
        catch (SQLException e) {
            throw new TrinoException(PHOENIX_METADATA_ERROR, "Couldn't get casing for the schema name", e);
        }
    }

    @Override
    public void createTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, boolean ignoreExisting)
    {
        phoenixClient.beginCreateTable(session, tableMetadata);
    }

    @Override
    public ConnectorOutputTableHandle beginCreateTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, Optional<ConnectorTableLayout> layout, RetryMode retryMode)
    {
        if (retryMode != NO_RETRIES) {
            throw new TrinoException(NOT_SUPPORTED, "This connector does not support query retries");
        }
        return phoenixClient.beginCreateTable(session, tableMetadata);
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishCreateTable(ConnectorSession session, ConnectorOutputTableHandle tableHandle, Collection<Slice> fragments, Collection<ComputedStatistics> computedStatistics)
    {
        return Optional.empty();
    }

    @Override
    public ConnectorInsertTableHandle beginInsert(ConnectorSession session, ConnectorTableHandle tableHandle, List<ColumnHandle> columns, RetryMode retryMode)
    {
        if (retryMode != NO_RETRIES) {
            throw new TrinoException(NOT_SUPPORTED, "This connector does not support query retries");
        }
        JdbcTableHandle handle = (JdbcTableHandle) tableHandle;
        Optional<String> rowkeyColumn = phoenixClient.getColumns(session, handle).stream()
                .map(JdbcColumnHandle::getColumnName)
                .filter(ROWKEY::equalsIgnoreCase)
                .findFirst();

        List<JdbcColumnHandle> columnHandles = columns.stream()
                .map(JdbcColumnHandle.class::cast)
                .collect(toImmutableList());

        RemoteTableName remoteTableName = handle.asPlainTable().getRemoteTableName();
        return new PhoenixOutputTableHandle(
                remoteTableName.getSchemaName().orElse(null),
                remoteTableName.getTableName(),
                columnHandles.stream().map(JdbcColumnHandle::getColumnName).collect(toImmutableList()),
                columnHandles.stream().map(JdbcColumnHandle::getColumnType).collect(toImmutableList()),
                Optional.of(columnHandles.stream().map(JdbcColumnHandle::getJdbcTypeHandle).collect(toImmutableList())),
                rowkeyColumn);
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishInsert(ConnectorSession session, ConnectorInsertTableHandle insertHandle, Collection<Slice> fragments, Collection<ComputedStatistics> computedStatistics)
    {
        return Optional.empty();
    }

    @Override
    public void addColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnMetadata column)
    {
        if (column.getComment() != null) {
            throw new TrinoException(NOT_SUPPORTED, "This connector does not support adding columns with comments");
        }

        JdbcTableHandle handle = (JdbcTableHandle) tableHandle;
        RemoteTableName remoteTableName = handle.asPlainTable().getRemoteTableName();
        phoenixClient.execute(session, format(
                "ALTER TABLE %s ADD %s %s",
                getEscapedTableName(remoteTableName.getSchemaName().orElse(null), remoteTableName.getTableName()),
                phoenixClient.quoted(column.getName()),
                phoenixClient.toWriteMapping(session, column.getType()).getDataType()));
    }

    @Override
    public void dropColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle column)
    {
        JdbcTableHandle handle = (JdbcTableHandle) tableHandle;
        JdbcColumnHandle columnHandle = (JdbcColumnHandle) column;
        RemoteTableName remoteTableName = handle.asPlainTable().getRemoteTableName();
        phoenixClient.execute(session, format(
                "ALTER TABLE %s DROP COLUMN %s",
                getEscapedTableName(remoteTableName.getSchemaName().orElse(null), remoteTableName.getTableName()),
                phoenixClient.quoted(columnHandle.getColumnName())));
    }

    @Override
    public void dropTable(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        // if we autogenerated a ROWKEY for this table, delete the associated sequence as well
        boolean hasRowkey = getColumnHandles(session, tableHandle).values().stream()
                .map(JdbcColumnHandle.class::cast)
                .map(JdbcColumnHandle::getColumnName)
                .anyMatch(ROWKEY::equals);
        if (hasRowkey) {
            JdbcTableHandle jdbcHandle = (JdbcTableHandle) tableHandle;
            RemoteTableName remoteTableName = jdbcHandle.asPlainTable().getRemoteTableName();
            phoenixClient.execute(session, format("DROP SEQUENCE %s", getEscapedTableName(remoteTableName.getSchemaName().orElse(null), remoteTableName.getTableName() + "_sequence")));
        }
        phoenixClient.dropTable(session, (JdbcTableHandle) tableHandle);
    }

    @Override
    public RowChangeParadigm getRowChangeParadigm(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        return CHANGE_ONLY_UPDATED_COLUMNS;
    }

    @Override
    public JdbcColumnHandle getMergeRowIdColumnHandle(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        JdbcTableHandle handle = (JdbcTableHandle) tableHandle;

        List<RowType.Field> fields = phoenixClient.getPrimaryKeyColumnHandles(session, handle).stream()
                .map(columnHandle -> new RowType.Field(Optional.of(columnHandle.getColumnName()), columnHandle.getColumnType()))
                .collect(toImmutableList());
        verify(!fields.isEmpty(), "Phoenix primary key is empty");

        return new JdbcColumnHandle(
                MERGE_ROW_ID_COLUMN_NAME,
                new JdbcTypeHandle(Types.ROWID, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                RowType.from(fields));
    }

    @Override
    public ConnectorMergeTableHandle beginMerge(ConnectorSession session, ConnectorTableHandle tableHandle, RetryMode retryMode)
    {
        JdbcTableHandle handle = (JdbcTableHandle) tableHandle;
        checkArgument(handle.isNamedRelation(), "Merge target must be named relation table");
        JdbcTableHandle plainTable = phoenixClient.buildPlainTable(handle);
        JdbcColumnHandle mergeRowIdColumnHandle = getMergeRowIdColumnHandle(session, plainTable);

        List<JdbcColumnHandle> columns = phoenixClient.getColumns(session, plainTable).stream()
                .filter(column -> !ROWKEY.equalsIgnoreCase(column.getColumnName()))
                .collect(toImmutableList());
        PhoenixOutputTableHandle phoenixOutputTableHandle = (PhoenixOutputTableHandle) beginInsert(session, plainTable, ImmutableList.copyOf(columns), retryMode);

        return new PhoenixMergeTableHandle(
                phoenixClient.updatedScanColumnTable(session, handle, handle.getColumns(), mergeRowIdColumnHandle),
                phoenixOutputTableHandle,
                mergeRowIdColumnHandle);
    }

    @Override
    public void finishMerge(ConnectorSession session, ConnectorMergeTableHandle mergeTableHandle, Collection<Slice> fragments, Collection<ComputedStatistics> computedStatistics)
    {
    }

    @Override
    public void truncateTable(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support truncating tables");
    }

    @Override
    public Optional<AggregationApplicationResult<ConnectorTableHandle>> applyAggregation(
            ConnectorSession session,
            ConnectorTableHandle table,
            List<AggregateFunction> aggregates,
            Map<String, ColumnHandle> assignments,
            List<List<ColumnHandle>> groupingSets)
    {
        // TODO support aggregation pushdown
        return Optional.empty();
    }
}
