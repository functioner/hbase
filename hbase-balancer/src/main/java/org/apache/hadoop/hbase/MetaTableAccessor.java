/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell.Type;
import org.apache.hadoop.hbase.ClientCatalogAccessor.QueryType;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Consistency;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.RegionInfoBuilder;
import org.apache.hadoop.hbase.client.RegionReplicaUtil;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableState;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.RowFilter;
import org.apache.hadoop.hbase.filter.SubstringComparator;
import org.apache.hadoop.hbase.master.RegionState;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.ExceptionUtil;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.util.PairOfSameType;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hbase.thirdparty.com.google.common.annotations.VisibleForTesting;

/**
 * This class is going to be renamed to CatalogAccessor. See note in CatalogAccessor.
 */
@InterfaceAudience.Private
public class MetaTableAccessor {

  private static final Logger LOG = LoggerFactory.getLogger(CatalogAccessor.class);
  private static final Logger CATALOGLOG =
    LoggerFactory.getLogger("org.apache.hadoop.hbase.CATALOG");

  MetaTableAccessor() {
  }

  ////////////////////////
  // Reading operations //
  ////////////////////////

  /**
   * This scans hbase:meta only. Method will be renamed once this class
   * is fully replaced by CatalogAccessor.
   *
   * Performs a full scan of <code>hbase:meta</code> for regions.
   * @param connection connection we're using
   * @param visitor Visitor invoked against each row in regions family.
   */
  public static void fullScanRegions(Connection connection,
    final ClientCatalogAccessor.Visitor visitor) throws IOException {
    scanMeta(connection, null, null, QueryType.REGION, visitor);
  }

  /**
   * Performs a full scan of the specified catalog table for regions.
   *
   * @param catalogTableName the catalog table to scan
   * @param connection connection we're using
   * @param visitor Visitor invoked against each row in regions family.
   */
  public static void fullScanRegions(Connection connection, TableName catalogTableName,
    final ClientCatalogAccessor.Visitor visitor) throws IOException {
    scanCatalog(connection, catalogTableName, null, null, ClientCatalogAccessor.QueryType.REGION,
      null, Integer.MAX_VALUE, visitor);
  }

  /**
   * This scans hbase:meta only. Method will be renamed (or replaced) once this class
   * is fully replaced by CatalogAccessor.
   *
   * Performs a full scan of <code>hbase:meta</code> for regions.
   * @param connection connection we're using
   */
  public static List<Result> fullScanRegions(Connection connection) throws IOException {
    return fullScan(connection, QueryType.REGION);
  }

  /**
   * Performs a full scan of <code>hbase:meta</code> for tables.
   * @param connection connection we're using
   * @param visitor Visitor invoked against each row in tables family.
   */
  public static void fullScanTables(Connection connection,
    final ClientCatalogAccessor.Visitor visitor) throws IOException {
    scanMeta(connection, null, null, QueryType.TABLE, visitor);
  }

  /**
   * Performs a full scan of <code>hbase:meta</code>.
   * @param connection connection we're using
   * @param type scanned part of meta
   * @return List of {@link Result}
   */
  private static List<Result> fullScan(Connection connection, QueryType type) throws IOException {
    ClientCatalogAccessor.CollectAllVisitor v = new ClientCatalogAccessor.CollectAllVisitor();
    scanMeta(connection, null, null, type, v);
    return v.getResults();
  }

  /**
   * Callers should call close on the returned {@link Table} instance.
   * @param connection connection we're using to access Meta
   * @return An {@link Table} for <code>hbase:meta</code>
   * @throws NullPointerException if {@code connection} is {@code null}
   */
  public static Table getMetaHTable(final Connection connection) throws IOException {
    // We used to pass whole CatalogTracker in here, now we just pass in Connection
    Objects.requireNonNull(connection, "Connection cannot be null");
    if (connection.isClosed()) {
      throw new IOException("connection is closed");
    }
    return connection.getTable(TableName.META_TABLE_NAME);
  }

  /**
   * Callers should call close on the returned {@link Table} instance.
   * @param connection connection we're using to access Meta
   * @return An {@link Table} for <code>hbase:meta</code>
   * @throws NullPointerException if {@code connection} is {@code null}
   */
  public static Table getCatalogHTable(final Connection connection, TableName catalogTableName)
    throws IOException {
    if (!isCatalogTable(catalogTableName)) {
      throw new IllegalStateException("Table supplied is not a catalog table: " + catalogTableName);
    }
    // We used to pass whole CatalogTracker in here, now we just pass in Connection
    Objects.requireNonNull(connection, "Connection cannot be null");
    if (connection.isClosed()) {
      throw new IOException("connection is closed");
    }
    return connection.getTable(catalogTableName);
  }

  /**
   * Gets the region info and assignment for the specified region.
   * @param connection connection we're using
   * @param regionName Region to lookup.
   * @return Location and RegionInfo for <code>regionName</code>
   * @deprecated use {@link #getRegionLocation(Connection, byte[])} instead
   */
  @Deprecated
  public static Pair<RegionInfo, ServerName> getRegion(Connection connection, byte[] regionName)
    throws IOException {
    HRegionLocation location = getRegionLocation(connection, regionName);
    return location == null ? null : new Pair<>(location.getRegion(), location.getServerName());
  }

  /**
   * Returns the HRegionLocation from the appropriate catalog table for the given region
   * @param connection connection we're using
   * @param regionName region we're looking for
   * @return HRegionLocation for the given region or null if not found
   */
  public static HRegionLocation getRegionLocation(Connection connection, byte[] regionName)
    throws IOException {
    byte[] row;
    RegionInfo parsedInfo = null;
    try {
      parsedInfo = CatalogFamilyFormat.parseRegionInfoFromRegionName(regionName);
      row = CatalogFamilyFormat.getCatalogKeyForRegion(parsedInfo);
    } catch (Exception parseEx) {
      // This is used with tableName passed as regionName.
      return null;
    }
    Get get = new Get(row);
    get.addFamily(HConstants.CATALOG_FAMILY);
    Result r;
    try (Table t = getCatalogHTable(connection, getCatalogTableForTable(parsedInfo.getTable()))) {
      r = t.get(get);
    }
    RegionLocations locations = CatalogFamilyFormat.getRegionLocations(r);
    return locations == null ? null :
      locations.getRegionLocation(parsedInfo == null ? 0 : parsedInfo.getReplicaId());
  }

  /**
   * Returns the HRegionLocation from the appropriate catalog table for the given region
   * @param connection connection we're using
   * @param regionInfo region information
   * @return HRegionLocation for the given region
   */
  public static HRegionLocation getRegionLocation(Connection connection, RegionInfo regionInfo)
    throws IOException {
    return CatalogFamilyFormat.getRegionLocation(getCatalogFamilyRow(connection, regionInfo),
      regionInfo, regionInfo.getReplicaId());
  }

  /**
   * @return Return the {@link HConstants#CATALOG_FAMILY} row from the appropriate catalog table.
   */
  public static Result getCatalogFamilyRow(Connection connection, RegionInfo ri)
    throws IOException {
    Get get = new Get(CatalogFamilyFormat.getCatalogKeyForRegion(ri));
    get.addFamily(HConstants.CATALOG_FAMILY);
    try (Table t = getCatalogHTable(connection, getCatalogTableForTable(ri.getTable()))) {
      return t.get(get);
    }
  }

  /**
   * @return the table name of the catalog that contains the passed table name
   */
  public static TableName getCatalogTableForTable(TableName tableName) {
    if (TableName.ROOT_TABLE_NAME.equals(tableName)) {
      throw new IllegalStateException("Can't get catalog table for hbase:root table");
    }
    if (TableName.META_TABLE_NAME.equals(tableName)) {
      return TableName.ROOT_TABLE_NAME;
    }
    return TableName.META_TABLE_NAME;
  }

  /**
   * @return true if the passed tableName is a catalog table (eg hbase:root or hbase:meta)
   */
  public static boolean isCatalogTable(TableName tableName) {
    return tableName.equals(TableName.ROOT_TABLE_NAME) ||
      tableName.equals(TableName.META_TABLE_NAME);
  }

  /**
   * Gets the result in the appropriate catalog table for the specified region.
   * @param connection connection we're using
   * @param regionName region we're looking for
   * @return result of the specified region
   */
  public static Result getRegionResult(Connection connection,
    byte[] regionName) throws IOException {
    Table catalogTable = null;
    if (Bytes.equals(RegionInfoBuilder.ROOT_REGIONINFO.getRegionName(), regionName)) {
      throw new IllegalStateException("This method cannot be used for hbase:root region");
    }
    Get get = new Get(regionName);
    get.addFamily(HConstants.CATALOG_FAMILY);
    catalogTable = getCatalogHTable(connection,
      getCatalogTableForTable(
        CatalogFamilyFormat.parseRegionInfoFromRegionName(regionName).getTable()));
    try (Table t = catalogTable) {
      return t.get(get);
    }
  }

  /**
   * Scans catalog tables for a row whose key contains the specified <B>regionEncodedName</B>, returning
   * a single related <code>Result</code> instance if any row is found, null otherwise.
   * @param connection the connection to query the catalog tables.
   * @param regionEncodedName the region encoded name to look for in the catalog.
   * @return <code>Result</code> instance with the row related info in the catalogtables,
   * null otherwise.
   * @throws IOException if any errors occur while querying the catalog tables.
   */
  public static Result scanByRegionEncodedName(Connection connection, String regionEncodedName)
    throws IOException {
    RowFilter rowFilter =
      new RowFilter(CompareOperator.EQUAL, new SubstringComparator(regionEncodedName));
    Scan scan = getCatalogScan(connection.getConfiguration(), 1);
    scan.setFilter(rowFilter);
    Result res = null;
    try (ResultScanner resultScanner =
      getCatalogHTable(connection, TableName.META_TABLE_NAME).getScanner(scan)) {
      res = resultScanner.next();
    }
    if (res == null) {
      scan = getCatalogScan(connection.getConfiguration(), 1);
      scan.setFilter(rowFilter);
      try (ResultScanner resultScanner =
        getCatalogHTable(connection, TableName.ROOT_TABLE_NAME).getScanner(scan)) {
        res = resultScanner.next();
      }
    }
    return res;
  }

  /**
   * This method will be renamed/replaced once this class is fully deprecated and replaced
   * by CatalogAccessor.
   *
   * Lists all of the regions currently in META.
   * @param connection to connect with
   * @param excludeOfflinedSplitParents False if we are to include offlined/splitparents regions,
   *          true and we'll leave out offlined regions from returned list
   * @return List of all user-space regions.
   */
  @VisibleForTesting
  public static List<RegionInfo> getAllRegions(Connection connection,
    boolean excludeOfflinedSplitParents) throws IOException {
    List<Pair<RegionInfo, ServerName>> result;

    result = getTableRegionsAndLocations(connection, null, excludeOfflinedSplitParents);

    return getListOfRegionInfos(result);

  }

  /**
   * Gets all of the regions of the specified table. Do not use this method to get root table
   * regions, use methods in RootTableLocator instead.
   * @param connection connection we're using
   * @param tableName table we're looking for
   * @return Ordered list of {@link RegionInfo}.
   */
  public static List<RegionInfo> getTableRegions(Connection connection, TableName tableName)
    throws IOException {
    return getTableRegions(connection, tableName, false);
  }

  /**
   * Gets all of the regions of the specified table. Do not use this method to get root table
   * regions, use methods in RootTableLocator instead.
   * @param connection connection we're using
   * @param tableName table we're looking for
   * @param excludeOfflinedSplitParents If true, do not include offlined split parents in the
   *          return.
   * @return Ordered list of {@link RegionInfo}.
   */
  public static List<RegionInfo> getTableRegions(Connection connection, TableName tableName,
    final boolean excludeOfflinedSplitParents) throws IOException {
    List<Pair<RegionInfo, ServerName>> result =
      getTableRegionsAndLocations(connection, tableName, excludeOfflinedSplitParents);
    return getListOfRegionInfos(result);
  }

  private static List<RegionInfo>
  getListOfRegionInfos(final List<Pair<RegionInfo, ServerName>> pairs) {
    if (pairs == null || pairs.isEmpty()) {
      return Collections.emptyList();
    }
    List<RegionInfo> result = new ArrayList<>(pairs.size());
    for (Pair<RegionInfo, ServerName> pair : pairs) {
      result.add(pair.getFirst());
    }
    return result;
  }

  /**
   * This method creates a Scan object that will only scan catalog rows that belong to the specified
   * table. It doesn't specify any columns. This is a better alternative to just using a start row
   * and scan until it hits a new table since that requires parsing the HRI to get the table name.
   * @param tableName bytes of table's name
   * @return configured Scan object
   */
  public static Scan getScanForTableName(Configuration conf, TableName tableName) {
    // Start key is just the table name with delimiters
    byte[] startKey = ClientCatalogAccessor.getTableStartRowForCatalog(tableName, QueryType.REGION);
    // Stop key appends the smallest possible char to the table name
    byte[] stopKey = ClientCatalogAccessor.getTableStopRowForCatalog(tableName, QueryType.REGION);

    Scan scan = getCatalogScan(conf, -1);
    scan.withStartRow(startKey);
    scan.withStopRow(stopKey);
    return scan;
  }

  private static Scan getCatalogScan(Configuration conf, int rowUpperLimit) {
    Scan scan = new Scan();
    int scannerCaching = conf.getInt(HConstants.HBASE_CATALOG_SCANNER_CACHING,
      HConstants.DEFAULT_HBASE_CATALOG_SCANNER_CACHING);
    if (conf.getBoolean(HConstants.USE_META_REPLICAS, HConstants.DEFAULT_USE_META_REPLICAS)) {
      scan.setConsistency(Consistency.TIMELINE);
    }
    if (rowUpperLimit > 0) {
      scan.setLimit(rowUpperLimit);
      scan.setReadType(Scan.ReadType.PREAD);
    }
    scan.setCaching(scannerCaching);
    return scan;
  }

  /**
   * Do not use this method to get root table regions, use methods in RootTableLocator instead.
   * @param connection connection we're using
   * @param tableName table we're looking for
   * @return Return list of regioninfos and server.
   */
  public static List<Pair<RegionInfo, ServerName>>
    getTableRegionsAndLocations(Connection connection, TableName tableName) throws IOException {
    return getTableRegionsAndLocations(connection, tableName, true);
  }

  /**
   * Do not use this method to get root table regions, use methods in RootTableLocator instead.
   * @param connection connection we're using
   * @param tableName table to work with, can be null for getting all regions
   * @param excludeOfflinedSplitParents don't return split parents
   * @return Return list of regioninfos and server addresses.
   */
  // What happens here when 1M regions in hbase:meta? This won't scale?
  public static List<Pair<RegionInfo, ServerName>> getTableRegionsAndLocations(
    Connection connection, @Nullable final TableName tableName,
    final boolean excludeOfflinedSplitParents) throws IOException {
    if (tableName != null && tableName.equals(TableName.ROOT_TABLE_NAME)) {
      throw new IOException(
        "This method can't be used to locate root regions; use RootTableLocator instead");
    }
    // Make a version of CollectingVisitor that collects RegionInfo and ServerAddress
    ClientCatalogAccessor.CollectRegionLocationsVisitor visitor =
      new ClientCatalogAccessor.CollectRegionLocationsVisitor(excludeOfflinedSplitParents);
    byte[] startRow = ClientCatalogAccessor.getTableStartRowForCatalog(tableName, QueryType.REGION);
    byte[] stopRow = ClientCatalogAccessor.getTableStopRowForCatalog(tableName, QueryType.REGION);
    TableName parentTable = TableName.META_TABLE_NAME;
    if (TableName.META_TABLE_NAME.equals(tableName)) {
      parentTable = TableName.ROOT_TABLE_NAME;
      startRow = null;
      stopRow = null;
    }

    scanCatalog(connection,
      parentTable,
      startRow,
      stopRow,
      ClientCatalogAccessor.QueryType.REGION,
      Integer.MAX_VALUE,
      visitor);
    return visitor.getResults();
  }

  public static void fullScanMetaAndPrint(Connection connection) throws IOException {
    ClientCatalogAccessor.Visitor v = r -> {
      if (r == null || r.isEmpty()) {
        return true;
      }
      LOG.info("fullScanMetaAndPrint.Current Meta Row: " + r);
      TableState state = CatalogFamilyFormat.getTableState(r);
      if (state != null) {
        LOG.info("fullScanMetaAndPrint.Table State={}" + state);
      } else {
        RegionLocations locations = CatalogFamilyFormat.getRegionLocations(r);
        if (locations == null) {
          return true;
        }
        for (HRegionLocation loc : locations.getRegionLocations()) {
          if (loc != null) {
            LOG.info("fullScanMetaAndPrint.HRI Print={}", loc.getRegion());
          }
        }
      }
      return true;
    };
    scanMeta(connection, null, null, QueryType.ALL, v);
  }

  public static void scanMetaForTableRegions(Connection connection,
    ClientCatalogAccessor.Visitor visitor, TableName tableName) throws IOException {
    scanCatalogForTableRegions(connection, QueryType.REGION, Integer.MAX_VALUE, visitor, tableName);
  }

  public static void scanCatalogForTableRegions(Connection connection, QueryType type, int maxRows,
    final ClientCatalogAccessor.Visitor visitor, TableName table) throws IOException {
    scanCatalog(connection, getCatalogTableForTable(table),
      ClientCatalogAccessor.getTableStartRowForCatalog(table, type),
      ClientCatalogAccessor.getTableStopRowForCatalog(table, type),
      type, maxRows, visitor);
  }

  public static void scanMeta(Connection connection, @Nullable final byte[] startRow,
    @Nullable final byte[] stopRow, QueryType type, final ClientCatalogAccessor.Visitor visitor)
    throws IOException {
    scanCatalog(connection, TableName.META_TABLE_NAME, startRow, stopRow, type, Integer.MAX_VALUE,
      visitor);
  }

  /**
   * Performs a scan of META table for given table starting from given row.
   * @param connection connection we're using
   * @param visitor visitor to call
   * @param tableName table withing we scan
   * @param row start scan from this row
   * @param rowLimit max number of rows to return
   */
  public static void scanMeta(Connection connection, final ClientCatalogAccessor.Visitor visitor,
    final TableName tableName, final byte[] row, final int rowLimit) throws IOException {
    byte[] startRow = null;
    byte[] stopRow = null;
    if (tableName != null) {
      startRow = ClientCatalogAccessor.getTableStartRowForCatalog(tableName, QueryType.REGION);
      if (row != null) {
        RegionInfo closestRi = getClosestRegionInfo(connection, tableName, row);
        startRow =
          RegionInfo.createRegionName(tableName, closestRi.getStartKey(), HConstants.ZEROES, false);
      }
      stopRow = ClientCatalogAccessor.getTableStopRowForCatalog(tableName, QueryType.REGION);
    }
    scanCatalog(connection, TableName.META_TABLE_NAME, startRow, stopRow, QueryType.REGION,
      rowLimit, visitor);
  }

  /**
   * Performs a scan of specified table.
   * @param connection connection we're using
   * @param catalogTable the catalog table to scan
   * @param startRow Where to start the scan. Pass null if want to begin scan at first row.
   * @param stopRow Where to stop the scan. Pass null if want to scan all rows from the start one
   * @param type scanned part of meta
   * @param maxRows maximum rows to return
   * @param visitor Visitor invoked against each row.
   */
  public static void scanCatalog(Connection connection, TableName catalogTable,
    @Nullable final byte[] startRow, @Nullable final byte[] stopRow, QueryType type, int maxRows,
    final ClientCatalogAccessor.Visitor visitor) throws IOException {
    scanCatalog(connection, catalogTable, startRow, stopRow, type, null, maxRows, visitor);
  }

  public static void scanMeta(Connection connection,
    @Nullable final byte[] startRow, @Nullable final byte[] stopRow, QueryType type, int maxRows,
    final ClientCatalogAccessor.Visitor visitor) throws IOException {
    scanCatalog(connection, TableName.META_TABLE_NAME, startRow, stopRow, type, maxRows, visitor);
  }

  public static void scanMeta(Connection connection,
    @Nullable final byte[] startRow, @Nullable final byte[] stopRow,
    QueryType type, @Nullable Filter filter, int maxRows,
    final ClientCatalogAccessor.Visitor visitor) throws IOException {
    scanCatalog(connection,
      TableName.META_TABLE_NAME, startRow, stopRow, type, filter, maxRows, visitor);
  }

  public static void scanCatalog(Connection connection, TableName catalogTableName,
    @Nullable final byte[] startRow, @Nullable final byte[] stopRow,
    QueryType type, @Nullable Filter filter, int maxRows,
    final ClientCatalogAccessor.Visitor visitor) throws IOException {
    int rowUpperLimit = maxRows > 0 ? maxRows : Integer.MAX_VALUE;
    Scan scan = getCatalogScan(connection.getConfiguration(), rowUpperLimit);

    for (byte[] family : type.getFamilies()) {
      scan.addFamily(family);
    }
    if (startRow != null) {
      scan.withStartRow(startRow);
    }
    if (stopRow != null) {
      scan.withStopRow(stopRow);
    }
    if (filter != null) {
      scan.setFilter(filter);
    }

    if (LOG.isTraceEnabled()) {
      LOG.trace("Scanning " + catalogTableName + " starting at row=" +
        Bytes.toStringBinary(startRow) + " stopping at row=" + Bytes.toStringBinary(stopRow) +
        " for max=" + rowUpperLimit + " with caching=" + scan.getCaching());
    }

    int currentRow = 0;
    try (Table catalogTable = getCatalogHTable(connection, catalogTableName)) {
      try (ResultScanner scanner = catalogTable.getScanner(scan)) {
        Result data;
        while ((data = scanner.next()) != null) {
          if (data.isEmpty()) {
            continue;
          }
          // Break if visit returns false.
          if (!visitor.visit(data)) {
            break;
          }
          if (++currentRow >= rowUpperLimit) {
            break;
          }
        }
      }
    }
    if (visitor instanceof Closeable) {
      try {
        ((Closeable) visitor).close();
      } catch (Throwable t) {
        ExceptionUtil.rethrowIfInterrupt(t);
        LOG.debug("Got exception in closing the meta scanner visitor", t);
      }
    }
  }

  /**
   * @return Get closest catalog table region row to passed <code>row</code>
   */
  @NonNull
  private static RegionInfo getClosestRegionInfo(Connection connection,
    @NonNull final TableName tableName, @NonNull final byte[] row) throws IOException {
    byte[] searchRow =
      RegionInfo.createRegionName(tableName, row, HConstants.NINES, false);
    Scan scan = getCatalogScan(connection.getConfiguration(), 1);
    scan.setReversed(true);
    scan.withStartRow(searchRow);
    try (ResultScanner resultScanner =
      getCatalogHTable(connection, getCatalogTableForTable(tableName)).getScanner(scan)) {
      Result result = resultScanner.next();
      if (result == null) {
        throw new TableNotFoundException("Cannot find row in META " + " for table: " + tableName +
          ", row=" + Bytes.toStringBinary(row));
      }
      RegionInfo regionInfo = CatalogFamilyFormat.getRegionInfo(result);
      if (regionInfo == null) {
        throw new IOException("RegionInfo was null or empty in Meta for " + tableName + ", row=" +
          Bytes.toStringBinary(row));
      }
      return regionInfo;
    }
  }

  /**
   * Returns the {@link ServerName} from catalog table {@link Result} where the region is
   * transitioning on. It should be the same as
   * {@link CatalogFamilyFormat#getServerName(Result,int)} if the server is at OPEN state.
   * @param r Result to pull the transitioning server name from
   * @return A ServerName instance or {@link CatalogFamilyFormat#getServerName(Result,int)} if
   *         necessary fields not found or empty.
   */
  @Nullable
  public static ServerName getTargetServerName(final Result r, final int replicaId) {
    final Cell cell = r.getColumnLatestCell(HConstants.CATALOG_FAMILY,
      CatalogFamilyFormat.getServerNameColumn(replicaId));
    if (cell == null || cell.getValueLength() == 0) {
      RegionLocations locations = CatalogFamilyFormat.getRegionLocations(r);
      if (locations != null) {
        HRegionLocation location = locations.getRegionLocation(replicaId);
        if (location != null) {
          return location.getServerName();
        }
      }
      return null;
    }
    return ServerName.parseServerName(
      Bytes.toString(cell.getValueArray(), cell.getValueOffset(), cell.getValueLength()));
  }

  /**
   * Returns the daughter regions by reading the corresponding columns of the catalog table Result.
   * @param data a Result object from the catalog table scan
   * @return pair of RegionInfo or PairOfSameType(null, null) if region is not a split parent
   */
  public static PairOfSameType<RegionInfo> getDaughterRegions(Result data) {
    RegionInfo splitA = CatalogFamilyFormat.getRegionInfo(data, HConstants.SPLITA_QUALIFIER);
    RegionInfo splitB = CatalogFamilyFormat.getRegionInfo(data, HConstants.SPLITB_QUALIFIER);
    return new PairOfSameType<>(splitA, splitB);
  }

  /**
   * Fetch table state for given table from META table
   * @param conn connection to use
   * @param tableName table to fetch state for
   */
  @Nullable
  public static TableState getTableState(Connection conn, TableName tableName)
    throws IOException {
    if (tableName.equals(TableName.ROOT_TABLE_NAME) ||
      tableName.equals(TableName.META_TABLE_NAME)) {
      return new TableState(tableName, TableState.State.ENABLED);
    }
    Table metaHTable = getCatalogHTable(conn, TableName.META_TABLE_NAME);
    Get get = new Get(tableName.getName()).addColumn(HConstants.TABLE_FAMILY,
      HConstants.TABLE_STATE_QUALIFIER);
    Result result = metaHTable.get(get);
    return CatalogFamilyFormat.getTableState(result);
  }

  /**
   * Fetch table states from META table
   * @param conn connection to use
   * @return map {tableName -&gt; state}
   */
  public static Map<TableName, TableState> getTableStates(Connection conn) throws IOException {
    final Map<TableName, TableState> states = new LinkedHashMap<>();
    ClientCatalogAccessor.Visitor collector = r -> {
      TableState state = CatalogFamilyFormat.getTableState(r);
      if (state != null) {
        states.put(state.getTableName(), state);
      }
      return true;
    };
    fullScanTables(conn, collector);
    return states;
  }

  /**
   * Updates state in META Do not use. For internal use only.
   * @param conn connection to use
   * @param tableName table to look for
   */
  public static void updateTableState(Connection conn, TableName tableName, TableState.State actual)
    throws IOException {
    updateTableState(conn, new TableState(tableName, actual));
  }

  ////////////////////////
  // Editing operations //
  ////////////////////////
  /**
   * Generates and returns a Put containing the region into for the catalog table
   */
  public static Put makePutFromRegionInfo(RegionInfo regionInfo, long ts) throws IOException {
    return addRegionInfo(new Put(regionInfo.getRegionName(), ts), regionInfo);
  }

  /**
   * Generates and returns a Delete containing the region info for the catalog table
   */
  public static Delete makeDeleteFromRegionInfo(RegionInfo regionInfo, long ts) {
    if (regionInfo == null) {
      throw new IllegalArgumentException("Can't make a delete for null region");
    }
    Delete delete = new Delete(regionInfo.getRegionName());
    delete.addFamily(HConstants.CATALOG_FAMILY, ts);
    return delete;
  }

  /**
   * Adds split daughters to the Put
   */
  public static Put addDaughtersToPut(Put put, RegionInfo splitA, RegionInfo splitB)
    throws IOException {
    if (splitA != null) {
      put.add(CellBuilderFactory.create(CellBuilderType.SHALLOW_COPY).setRow(put.getRow())
        .setFamily(HConstants.CATALOG_FAMILY).setQualifier(HConstants.SPLITA_QUALIFIER)
        .setTimestamp(put.getTimestamp()).setType(Type.Put).setValue(RegionInfo.toByteArray(splitA))
        .build());
    }
    if (splitB != null) {
      put.add(CellBuilderFactory.create(CellBuilderType.SHALLOW_COPY).setRow(put.getRow())
        .setFamily(HConstants.CATALOG_FAMILY).setQualifier(HConstants.SPLITB_QUALIFIER)
        .setTimestamp(put.getTimestamp()).setType(Type.Put).setValue(RegionInfo.toByteArray(splitB))
        .build());
    }
    return put;
  }

  /**
   * Put the passed <code>p</code> to the specified catalog table.
   * @param connection connection we're using
   * @param catalogTable hbase:root or hbase:meta table
   * @param p Put to add to catalog table
   */
  private static void putToCatalogTable(Connection connection, TableName catalogTable, Put p)
    throws IOException {
    try (Table table = getCatalogHTable(connection, catalogTable)) {
      put(table, p);
    }
  }

  /**
   * @param t Table to use
   * @param p put to make
   */
  private static void put(Table t, Put p) throws IOException {
    debugLogMutation(t.getName(), p);
    t.put(p);
  }

  /**
   * Put the passed <code>ps</code> to the <code>hbase:meta</code> table.
   * @param connection connection we're using
   * @param ps Put to add to hbase:meta
   */
  public static void putsToMetaTable(final Connection connection, final List<Put> ps)
    throws IOException {
    putsToCatalogTable(connection, TableName.META_TABLE_NAME, ps);
  }

  /**
   * Put the passed <code>ps</code> to the <code>hbase:meta</code> table.
   * @param connection connection we're using
   * @param ps Put to add to hbase:meta
   */
  public static void putsToCatalogTable(final Connection connection, TableName tableName,
    final List<Put> ps)
    throws IOException {
    if (ps.isEmpty()) {
      return;
    }
    try (Table t = getCatalogHTable(connection, tableName)) {
      debugLogMutations(t.getName(), ps);
      // the implementation for putting a single Put is much simpler so here we do a check first.
      if (ps.size() == 1) {
        t.put(ps.get(0));
      } else {
        t.put(ps);
      }
    }
  }

  /**
   * Delete the passed <code>d</code> from the <code>hbase:meta</code> table.
   * @param connection connection we're using
   * @param d Delete to add to hbase:meta
   */
  private static void deleteFromCatalogTable(final Connection connection, TableName catalogTable,
    final Delete d) throws IOException {
    List<Delete> dels = new ArrayList<>(1);
    dels.add(d);
    deleteFromCatalogTable(connection, catalogTable, dels);
  }

  /**
   * Delete the passed <code>deletes</code> from the <code>hbase:meta</code> table.
   * @param connection connection we're using
   * @param deletes Deletes to add to hbase:meta  This list should support #remove.
   */
  private static void deleteFromCatalogTable(final Connection connection,
    TableName catalogTableName, final List<Delete> deletes) throws IOException {
    try (Table t = getCatalogHTable(connection, catalogTableName)) {
      debugLogMutations(catalogTableName, deletes);
      t.delete(deletes);
    }
  }

  public static Put addRegionStateToPut(Put put, RegionState.State state) throws IOException {
    put.add(CellBuilderFactory.create(CellBuilderType.SHALLOW_COPY).setRow(put.getRow())
      .setFamily(HConstants.CATALOG_FAMILY).setQualifier(HConstants.STATE_QUALIFIER)
      .setTimestamp(put.getTimestamp()).setType(Cell.Type.Put).setValue(Bytes.toBytes(state.name()))
      .build());
    return put;
  }

  /**
   * Update state column in hbase:meta.
   */
  public static void updateRegionState(Connection connection, RegionInfo ri,
    RegionState.State state) throws IOException {
    Put put = new Put(RegionReplicaUtil.getRegionInfoForDefaultReplica(ri).getRegionName());
    putsToCatalogTable(connection, getCatalogTableForTable(ri.getTable()),
      Collections.singletonList(addRegionStateToPut(put, state)));
  }

  /**
   * Adds daughter region infos to hbase:meta row for the specified region. Note that this does not
   * add its daughter's as different rows, but adds information about the daughters in the same row
   * as the parent. Use
   * {@link #splitRegion(Connection, RegionInfo, long, RegionInfo, RegionInfo, ServerName, int)} if
   * you want to do that.
   * @param connection connection we're using
   * @param regionInfo RegionInfo of parent region
   * @param splitA first split daughter of the parent regionInfo
   * @param splitB second split daughter of the parent regionInfo
   * @throws IOException if problem connecting or updating meta
   */
  public static void addSplitsToParent(Connection connection, RegionInfo regionInfo,
    RegionInfo splitA, RegionInfo splitB) throws IOException {
    TableName catalogTable = getCatalogTableForTable(regionInfo.getTable());
    try (Table catalog = getCatalogHTable(connection, catalogTable)) {
      Put put = makePutFromRegionInfo(regionInfo, EnvironmentEdgeManager.currentTime());
      addDaughtersToPut(put, splitA, splitB);
      catalog.put(put);
      debugLogMutation(catalogTable, put);
      LOG.debug("Added region {}", regionInfo.getRegionNameAsString());
    }
  }

  /**
   * Adds a hbase:meta row for each of the specified new regions. Initial state for new regions is
   * CLOSED.
   * @param connection connection we're using
   * @param regionInfos region information list
   * @throws IOException if problem connecting or updating meta
   */
  public static void addRegionsToMeta(Connection connection, List<RegionInfo> regionInfos,
    int regionReplication) throws IOException {
    addRegionsToMeta(connection, regionInfos, regionReplication,
      EnvironmentEdgeManager.currentTime());
  }

  /**
   * Adds a hbase:meta row for each of the specified new regions. Initial state for new regions is
   * CLOSED.
   * @param connection connection we're using
   * @param regionInfos region information list
   * @param ts desired timestamp
   * @throws IOException if problem connecting or updating meta
   */
  public static void addRegionsToMeta(Connection connection, List<RegionInfo> regionInfos,
    int regionReplication, long ts) throws IOException {
    List<Put> puts = new ArrayList<>();
    for (RegionInfo regionInfo : regionInfos) {
      if (!RegionReplicaUtil.isDefaultReplica(regionInfo)) {
        continue;
      }
      Put put = makePutFromRegionInfo(regionInfo, ts);
      // New regions are added with initial state of CLOSED.
      addRegionStateToPut(put, RegionState.State.CLOSED);
      // Add empty locations for region replicas so that number of replicas can be cached
      // whenever the primary region is looked up from meta
      for (int i = 1; i < regionReplication; i++) {
        addEmptyLocation(put, i);
      }
      puts.add(put);
    }
    putsToCatalogTable(connection, TableName.META_TABLE_NAME, puts);
    LOG.info("Added {} regions to {}.",puts.size(), TableName.META_TABLE_NAME);
  }

  /**
   * Update state of the table in meta.
   * @param connection what we use for update
   * @param state new state
   */
  private static void updateTableState(Connection connection, TableState state) throws IOException {
    Put put = makePutFromTableState(state, EnvironmentEdgeManager.currentTime());
    putToCatalogTable(connection, TableName.META_TABLE_NAME, put);
    LOG.info("Updated {} in hbase:meta", state);
  }

  /**
   * Construct PUT for given state
   * @param state new state
   */
  public static Put makePutFromTableState(TableState state, long ts) {
    Put put = new Put(state.getTableName().getName(), ts);
    put.addColumn(HConstants.TABLE_FAMILY, HConstants.TABLE_STATE_QUALIFIER,
      state.convert().toByteArray());
    return put;
  }

  /**
   * Remove state for table from meta
   * @param connection to use for deletion
   * @param table to delete state for
   */
  public static void deleteTableState(Connection connection, TableName table) throws IOException {
    long time = EnvironmentEdgeManager.currentTime();
    Delete delete = new Delete(table.getName());
    delete.addColumns(HConstants.TABLE_FAMILY, HConstants.TABLE_STATE_QUALIFIER, time);
    deleteFromCatalogTable(connection, TableName.META_TABLE_NAME, delete);
    LOG.info("Deleted table " + table + " state from Catalog");
  }

  /**
   * Updates the location of the specified region in hbase:meta to be the specified server hostname
   * and startcode.
   * <p>
   * Uses passed catalog tracker to get a connection to the server hosting the appropriate
   * catalog table region and makes edits to that region.
   * @param connection connection we're using
   * @param regionInfo region to update location of
   * @param openSeqNum the latest sequence number obtained when the region was open
   * @param sn Server name
   * @param masterSystemTime wall clock time from master if passed in the open region RPC
   */
  @VisibleForTesting
  public static void updateRegionLocation(Connection connection, RegionInfo regionInfo,
    ServerName sn, long openSeqNum, long masterSystemTime) throws IOException {
    updateLocation(connection, regionInfo, sn, openSeqNum, masterSystemTime);
  }

  /**
   * Updates the location of the specified region to be the specified server.
   * <p>
   * Connects to the specified server which should be hosting the specified catalog region name to
   * perform the edit.
   * @param connection connection we're using
   * @param regionInfo region to update location of
   * @param sn Server name
   * @param openSeqNum the latest sequence number obtained when the region was open
   * @param masterSystemTime wall clock time from master if passed in the open region RPC
   * @throws IOException In particular could throw {@link java.net.ConnectException} if the server
   *           is down on other end.
   */
  private static void updateLocation(Connection connection, RegionInfo regionInfo, ServerName sn,
    long openSeqNum, long masterSystemTime) throws IOException {
    // region replicas are kept in the primary region's row
    Put put = new Put(CatalogFamilyFormat.getCatalogKeyForRegion(regionInfo), masterSystemTime);
    addRegionInfo(put, regionInfo);
    addLocation(put, sn, openSeqNum, regionInfo.getReplicaId());
    putToCatalogTable(connection, getCatalogTableForTable(regionInfo.getTable()), put);
    LOG.info("Updated row {} with server=", regionInfo.getRegionNameAsString(), sn);
  }

  public static Put addRegionInfo(final Put p, final RegionInfo hri) throws IOException {
    p.add(CellBuilderFactory.create(CellBuilderType.SHALLOW_COPY).setRow(p.getRow())
      .setFamily(HConstants.CATALOG_FAMILY).setQualifier(HConstants.REGIONINFO_QUALIFIER)
      .setTimestamp(p.getTimestamp()).setType(Type.Put)
      // Serialize the Default Replica HRI otherwise scan of hbase:meta
      // shows an info:regioninfo value with encoded name and region
      // name that differs from that of the hbase;meta row.
      .setValue(RegionInfo.toByteArray(RegionReplicaUtil.getRegionInfoForDefaultReplica(hri)))
      .build());
    return p;
  }

  public static Put addLocation(Put p, ServerName sn, long openSeqNum, int replicaId)
    throws IOException {
    CellBuilder builder = CellBuilderFactory.create(CellBuilderType.SHALLOW_COPY);
    return p
      .add(builder.clear().setRow(p.getRow()).setFamily(HConstants.CATALOG_FAMILY)
        .setQualifier(CatalogFamilyFormat.getServerColumn(replicaId)).setTimestamp(p.getTimestamp())
        .setType(Cell.Type.Put).setValue(Bytes.toBytes(sn.getAddress().toString())).build())
      .add(builder.clear().setRow(p.getRow()).setFamily(HConstants.CATALOG_FAMILY)
        .setQualifier(CatalogFamilyFormat.getStartCodeColumn(replicaId))
        .setTimestamp(p.getTimestamp()).setType(Cell.Type.Put)
        .setValue(Bytes.toBytes(sn.getStartcode())).build())
      .add(builder.clear().setRow(p.getRow()).setFamily(HConstants.CATALOG_FAMILY)
        .setQualifier(CatalogFamilyFormat.getSeqNumColumn(replicaId)).setTimestamp(p.getTimestamp())
        .setType(Type.Put).setValue(Bytes.toBytes(openSeqNum)).build());
  }

  public static Put addEmptyLocation(Put p, int replicaId) throws IOException {
    CellBuilder builder = CellBuilderFactory.create(CellBuilderType.SHALLOW_COPY);
    return p
      .add(builder.clear().setRow(p.getRow()).setFamily(HConstants.CATALOG_FAMILY)
        .setQualifier(CatalogFamilyFormat.getServerColumn(replicaId)).setTimestamp(p.getTimestamp())
        .setType(Type.Put).build())
      .add(builder.clear().setRow(p.getRow()).setFamily(HConstants.CATALOG_FAMILY)
        .setQualifier(CatalogFamilyFormat.getStartCodeColumn(replicaId))
        .setTimestamp(p.getTimestamp()).setType(Cell.Type.Put).build())
      .add(builder.clear().setRow(p.getRow()).setFamily(HConstants.CATALOG_FAMILY)
        .setQualifier(CatalogFamilyFormat.getSeqNumColumn(replicaId)).setTimestamp(p.getTimestamp())
        .setType(Cell.Type.Put).build());
  }

  private static void debugLogMutations(TableName tableName, List<? extends Mutation> mutations)
    throws IOException {
    if (!CATALOGLOG.isDebugEnabled()) {
      return;
    }
    // Logging each mutation in separate line makes it easier to see diff between them visually
    // because of common starting indentation.
    for (Mutation mutation : mutations) {
      debugLogMutation(tableName, mutation);
    }
  }

  private static void debugLogMutation(TableName t, Mutation p) throws IOException {
    CATALOGLOG.debug("{} {} {}", t, p.getClass().getSimpleName(), p.toJSON());
  }
}