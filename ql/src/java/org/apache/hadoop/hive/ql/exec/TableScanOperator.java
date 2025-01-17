/**
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

package org.apache.hadoop.hive.ql.exec;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.FileUtils;
import org.apache.hadoop.hive.common.StatsSetupConst;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.ErrorMsg;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.metadata.VirtualColumn;
import org.apache.hadoop.hive.ql.plan.OperatorDesc;
import org.apache.hadoop.hive.ql.plan.TableDesc;
import org.apache.hadoop.hive.ql.plan.TableScanDesc;
import org.apache.hadoop.hive.ql.plan.api.OperatorType;
import org.apache.hadoop.hive.ql.stats.StatsCollectionTaskIndependent;
import org.apache.hadoop.hive.ql.stats.StatsCollectionContext;
import org.apache.hadoop.hive.ql.stats.StatsPublisher;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorUtils;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorUtils.ObjectInspectorCopyOption;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapred.JobConf;

/**
 * Table Scan Operator If the data is coming from the map-reduce framework, just
 * forward it. This will be needed as part of local work when data is not being
 * read as part of map-reduce framework
 **/
public class TableScanOperator extends Operator<TableScanDesc> implements
    Serializable {
  private static final long serialVersionUID = 1L;

  protected transient JobConf jc;
  private transient Configuration hconf;
  private transient boolean inputFileChanged = false;
  private TableDesc tableDesc;

  private transient Stat currentStat;
  private transient Map<String, Stat> stats;

  private transient int rowLimit = -1;
  private transient int currCount = 0;

  private String defaultPartitionName;

  public TableDesc getTableDesc() {
    return tableDesc;
  }

  public void setTableDesc(TableDesc tableDesc) {
    this.tableDesc = tableDesc;
  }

  /**
   * Other than gathering statistics for the ANALYZE command, the table scan operator
   * does not do anything special other than just forwarding the row. Since the table
   * data is always read as part of the map-reduce framework by the mapper. But, when this
   * assumption stops to be true, i.e table data won't be only read by the mapper, this
   * operator will be enhanced to read the table.
   **/
  @Override
  public void process(Object row, int tag) throws HiveException {
    if (rowLimit >= 0 && currCount++ >= rowLimit) {
      setDone(true);
      return;
    }
    if (conf != null && conf.isGatherStats()) {
      gatherStats(row);
    }
    forward(row, inputObjInspectors[tag]);
  }

  // Change the table partition for collecting stats
  @Override
  public void cleanUpInputFileChangedOp() throws HiveException {
    inputFileChanged = true;
    // If the file name to bucket number mapping is maintained, store the bucket number
    // in the execution context. This is needed for the following scenario:
    // insert overwrite table T1 select * from T2;
    // where T1 and T2 are sorted/bucketed by the same keys into the same number of buckets
    // Although one mapper per file is used (BucketizedInputHiveInput), it is possible that
    // any mapper can pick up any file (depending on the size of the files). The bucket number
    // corresponding to the input file is stored to name the output bucket file appropriately.
    Map<String, Integer> bucketNameMapping =
        (conf != null) ? conf.getBucketFileNameMapping() : null;
    if ((bucketNameMapping != null) && (!bucketNameMapping.isEmpty())) {
      Path currentInputPath = getExecContext().getCurrentInputPath();
      getExecContext().setFileId(Integer.toString(bucketNameMapping.get(
          currentInputPath.getName())));
    }
  }

  private void gatherStats(Object row) {
    // first row/call or a new partition
    if ((currentStat == null) || inputFileChanged) {
      String partitionSpecs;
      inputFileChanged = false;
      if (conf.getPartColumns() == null || conf.getPartColumns().size() == 0) {
        partitionSpecs = ""; // non-partitioned
      } else {
        // Figure out the partition spec from the input.
        // This is only done once for the first row (when stat == null)
        // since all rows in the same mapper should be from the same partition.
        List<Object> writable;
        List<String> values;
        int dpStartCol; // the first position of partition column
        assert inputObjInspectors[0].getCategory() == ObjectInspector.Category.STRUCT : "input object inspector is not struct";

        writable = new ArrayList<Object>(conf.getPartColumns().size());
        values = new ArrayList<String>(conf.getPartColumns().size());
        dpStartCol = 0;
        StructObjectInspector soi = (StructObjectInspector) inputObjInspectors[0];
        for (StructField sf : soi.getAllStructFieldRefs()) {
          String fn = sf.getFieldName();
          if (!conf.getPartColumns().contains(fn)) {
            dpStartCol++;
          } else {
            break;
          }
        }
        ObjectInspectorUtils.partialCopyToStandardObject(writable, row, dpStartCol, conf
            .getPartColumns().size(),
            (StructObjectInspector) inputObjInspectors[0], ObjectInspectorCopyOption.WRITABLE);

        for (Object o : writable) {
          // It's possible that a parition column may have NULL value, in which case the row belongs
          // to the special partition, __HIVE_DEFAULT_PARTITION__.
          values.add(o == null ? defaultPartitionName : o.toString());
        }
        partitionSpecs = FileUtils.makePartName(conf.getPartColumns(), values);
        if (isLogInfoEnabled) {
          LOG.info("Stats Gathering found a new partition spec = " + partitionSpecs);
        }
      }
      // find which column contains the raw data size (both partitioned and non partitioned
      int uSizeColumn = -1;
      StructObjectInspector soi = (StructObjectInspector) inputObjInspectors[0];
      for (int i = 0; i < soi.getAllStructFieldRefs().size(); i++) {
        if (soi.getAllStructFieldRefs().get(i).getFieldName()
            .equals(VirtualColumn.RAWDATASIZE.getName().toLowerCase())) {
          uSizeColumn = i;
          break;
        }
      }
      currentStat = stats.get(partitionSpecs);
      if (currentStat == null) {
        currentStat = new Stat();
        currentStat.setBookkeepingInfo(StatsSetupConst.RAW_DATA_SIZE, uSizeColumn);
        stats.put(partitionSpecs, currentStat);
      }
    }

    // increase the row count
    currentStat.addToStat(StatsSetupConst.ROW_COUNT, 1);

    // extract the raw data size, and update the stats for the current partition
    int rdSizeColumn = currentStat.getBookkeepingInfo(StatsSetupConst.RAW_DATA_SIZE);
    if(rdSizeColumn != -1) {
      List<Object> rdSize = new ArrayList<Object>(1);
      ObjectInspectorUtils.partialCopyToStandardObject(rdSize, row,
          rdSizeColumn, 1, (StructObjectInspector) inputObjInspectors[0],
          ObjectInspectorCopyOption.WRITABLE);
      currentStat.addToStat(StatsSetupConst.RAW_DATA_SIZE, (((LongWritable)rdSize.get(0)).get()));
    }

  }

  @Override
  protected void initializeOp(Configuration hconf) throws HiveException {
    super.initializeOp(hconf);
    inputFileChanged = false;

    if (conf == null) {
      return;
    }

    rowLimit = conf.getRowLimit();
    if (!conf.isGatherStats()) {
      return;
    }

    this.hconf = hconf;
    if (hconf instanceof JobConf) {
      jc = (JobConf) hconf;
    } else {
      // test code path
      jc = new JobConf(hconf);
    }

    defaultPartitionName = HiveConf.getVar(hconf, HiveConf.ConfVars.DEFAULTPARTITIONNAME);
    currentStat = null;
    stats = new HashMap<String, Stat>();
  }

  @Override
  public void closeOp(boolean abort) throws HiveException {
    if (conf != null) {
      if (conf.isGatherStats() && stats.size() != 0) {
        publishStats();
      }
    }
  }

  /**
   * The operator name for this operator type. This is used to construct the
   * rule for an operator
   *
   * @return the operator name
   **/
  @Override
  public String getName() {
    return getOperatorName();
  }

  static public String getOperatorName() {
    return "TS";
  }

  public void setNeededColumnIDs(List<Integer> orign_columns) {
    conf.setNeededColumnIDs(orign_columns);
  }

  public List<Integer> getNeededColumnIDs() {
    return conf.getNeededColumnIDs();
  }

  public void setNeededColumns(List<String> columnNames) {
    conf.setNeededColumns(columnNames);
  }

  public List<String> getNeededColumns() {
    return conf.getNeededColumns();
  }

  public void setReferencedColumns(List<String> referencedColumns) {
    conf.setReferencedColumns(referencedColumns);
  }

  public List<String> getReferencedColumns() {
    return conf.getReferencedColumns();
  }

  @Override
  public OperatorType getType() {
    return OperatorType.TABLESCAN;
  }

  private void publishStats() throws HiveException {
    boolean isStatsReliable = conf.isStatsReliable();

    // Initializing a stats publisher
    StatsPublisher statsPublisher = Utilities.getStatsPublisher(jc);
    StatsCollectionContext sc = new StatsCollectionContext(jc);
    sc.setStatsTmpDir(conf.getTmpStatsDir());
    if (!statsPublisher.connect(sc)) {
      // just return, stats gathering should not block the main query.
      if (isLogInfoEnabled) {
        LOG.info("StatsPublishing error: cannot connect to database.");
      }
      if (isStatsReliable) {
        throw new HiveException(ErrorMsg.STATSPUBLISHER_CONNECTION_ERROR.getErrorCodedMsg());
      }
      return;
    }

    String taskID = Utilities.getTaskIdFromFilename(Utilities.getTaskId(hconf));
    Map<String, String> statsToPublish = new HashMap<String, String>();

    for (String pspecs : stats.keySet()) {
      statsToPublish.clear();
      String prefix = Utilities.join(conf.getStatsAggPrefix(), pspecs);

      int maxKeyLength = conf.getMaxStatsKeyPrefixLength();
      String key = Utilities.getHashedStatsPrefix(prefix, maxKeyLength);
      if (!(statsPublisher instanceof StatsCollectionTaskIndependent)) {
        // stats publisher except counter or fs type needs postfix 'taskID'
        key = Utilities.join(prefix, taskID);
      }
      for(String statType : stats.get(pspecs).getStoredStats()) {
        statsToPublish.put(statType, Long.toString(stats.get(pspecs).getStat(statType)));
      }
      if (!statsPublisher.publishStat(key, statsToPublish)) {
        if (isStatsReliable) {
          throw new HiveException(ErrorMsg.STATSPUBLISHER_PUBLISHING_ERROR.getErrorCodedMsg());
        }
      }
      if (isLogInfoEnabled) {
	LOG.info("publishing : " + key + " : " + statsToPublish.toString());
      }
    }
    if (!statsPublisher.closeConnection(sc)) {
      if (isStatsReliable) {
        throw new HiveException(ErrorMsg.STATSPUBLISHER_CLOSING_ERROR.getErrorCodedMsg());
      }
    }
  }

  @Override
  public boolean supportSkewJoinOptimization() {
    return true;
  }

  @Override
  public boolean supportAutomaticSortMergeJoin() {
    return true;
  }

  @Override
  public Operator<? extends OperatorDesc> clone()
    throws CloneNotSupportedException {
    TableScanOperator ts = (TableScanOperator) super.clone();
    ts.setNeededColumnIDs(new ArrayList<Integer>(getNeededColumnIDs()));
    ts.setNeededColumns(new ArrayList<String>(getNeededColumns()));
    ts.setReferencedColumns(new ArrayList<String>(getReferencedColumns()));
    return ts;
  }

}
