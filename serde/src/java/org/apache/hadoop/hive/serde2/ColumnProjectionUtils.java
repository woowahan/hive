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

package org.apache.hadoop.hive.serde2;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.StringUtils;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;

/**
 * ColumnProjectionUtils.
 *
 */
public final class ColumnProjectionUtils {
  public static final Log LOG = LogFactory.getLog(ColumnProjectionUtils.class);

  public static final String READ_COLUMN_IDS_CONF_STR = "hive.io.file.readcolumn.ids";
  public static final String READ_ALL_COLUMNS = "hive.io.file.read.all.columns";
  public static final String READ_COLUMN_NAMES_CONF_STR = "hive.io.file.readcolumn.names";
  private static final String READ_COLUMN_IDS_CONF_STR_DEFAULT = "";
  private static final boolean READ_ALL_COLUMNS_DEFAULT = true;
  private static final Joiner CSV_JOINER = Joiner.on(",").skipNulls();

  /**
   * @deprecated for backwards compatibility with <= 0.12, use setReadAllColumns
   */
  @Deprecated
  public static void setFullyReadColumns(Configuration conf) {
    setReadAllColumns(conf);
  }

  /**
   * @deprecated for backwards compatibility with <= 0.12, use setReadAllColumns
   * and appendReadColumns
   */
  @Deprecated
  @VisibleForTesting
  public static void setReadColumnIDs(Configuration conf, List<Integer> ids) {
    setReadColumnIDConf(conf, READ_COLUMN_IDS_CONF_STR_DEFAULT);
    appendReadColumns(conf, ids);
  }

  /**
   * @deprecated for backwards compatibility with <= 0.12, use appendReadColumns
   */
  @Deprecated
  public static void appendReadColumnIDs(Configuration conf, List<Integer> ids) {
    appendReadColumns(conf, ids);
  }



  /**
   * Sets the <em>READ_ALL_COLUMNS</em> flag and removes any previously
   * set column ids.
   */
  public static void setReadAllColumns(Configuration conf) {
    conf.setBoolean(READ_ALL_COLUMNS, true);
    setReadColumnIDConf(conf, READ_COLUMN_IDS_CONF_STR_DEFAULT);
  }

  /**
   * Returns the <em>READ_ALL_COLUMNS</em> columns flag.
   */
  public static boolean isReadAllColumns(Configuration conf) {
    return conf.getBoolean(READ_ALL_COLUMNS, READ_ALL_COLUMNS_DEFAULT);
  }

  /**
   * Appends read columns' ids (start from zero). Once a column
   * is included in the list, a underlying record reader of a columnar file format
   * (e.g. RCFile and ORC) can know what columns are needed.
   */
  public static void appendReadColumns(Configuration conf, List<Integer> ids) {
    String id = toReadColumnIDString(ids);
    String old = conf.get(READ_COLUMN_IDS_CONF_STR, null);
    String newConfStr = id;
    if (old != null) {
      newConfStr = newConfStr + StringUtils.COMMA_STR + old;
    }
    setReadColumnIDConf(conf, newConfStr);
    // Set READ_ALL_COLUMNS to false
    conf.setBoolean(READ_ALL_COLUMNS, false);
  }

  /**
   * This method appends read column information to configuration to use for PPD. It is
   * currently called with information from TSOP. Names come from TSOP input RowSchema, and
   * IDs are the indexes inside the schema (which PPD assumes correspond to indexes inside the
   * files to PPD in; something that would be invalid in many cases of schema evolution).
   * @param conf Config to set values to.
   * @param ids Column ids.
   * @param names Column names.
   */
  public static void appendReadColumns(
      Configuration conf, List<Integer> ids, List<String> names) {
    if (ids.size() != names.size()) {
      LOG.warn("Read column counts do not match: "
          + ids.size() + " ids, " + names.size() + " names");
    }
    appendReadColumns(conf, ids);
    appendReadColumnNames(conf, names);
  }

  public static void appendReadColumns(
      StringBuilder readColumnsBuffer, StringBuilder readColumnNamesBuffer, List<Integer> ids,
      List<String> names) {
    CSV_JOINER.appendTo(readColumnsBuffer, ids);
    CSV_JOINER.appendTo(readColumnNamesBuffer, names);
  }

  /**
   * Returns an array of column ids(start from zero) which is set in the given
   * parameter <tt>conf</tt>.
   */
  public static List<Integer> getReadColumnIDs(Configuration conf) {
    String skips = conf.get(READ_COLUMN_IDS_CONF_STR, READ_COLUMN_IDS_CONF_STR_DEFAULT);
    String[] list = StringUtils.split(skips);
    List<Integer> result = new ArrayList<Integer>(list.length);
    for (String element : list) {
      // it may contain duplicates, remove duplicates
      // TODO: WTF? This would break many assumptions elsewhere if it did.
      //       Column names' and column ids' lists are supposed to be correlated.
      Integer toAdd = Integer.parseInt(element);
      if (!result.contains(toAdd)) {
        result.add(toAdd);
      } else if (LOG.isInfoEnabled()) {
        LOG.info("Duplicate ID " + toAdd + " in column ID list");
      }
    }
    return result;
  }

  public static String[] getReadColumnNames(Configuration conf) {
    String colNames = conf.get(READ_COLUMN_NAMES_CONF_STR, READ_COLUMN_IDS_CONF_STR_DEFAULT);
    if (colNames != null && !colNames.isEmpty()) {
      return colNames.split(",");
    }
    return new String[] {};
  }

  private static void setReadColumnIDConf(Configuration conf, String id) {
    if (id.trim().isEmpty()) {
      conf.set(READ_COLUMN_IDS_CONF_STR, READ_COLUMN_IDS_CONF_STR_DEFAULT);
    } else {
      conf.set(READ_COLUMN_IDS_CONF_STR, id);
    }
  }

  private static void appendReadColumnNames(Configuration conf, List<String> cols) {
    String old = conf.get(READ_COLUMN_NAMES_CONF_STR, "");
    StringBuilder result = new StringBuilder(old);
    boolean first = old.isEmpty();
    for(String col: cols) {
      if (first) {
        first = false;
      } else {
        result.append(',');
      }
      result.append(col);
    }
    conf.set(READ_COLUMN_NAMES_CONF_STR, result.toString());
  }

  private static String toReadColumnIDString(List<Integer> ids) {
    String id = "";
    for (int i = 0; i < ids.size(); i++) {
      if (i == 0) {
        id = id + ids.get(i);
      } else {
        id = id + StringUtils.COMMA_STR + ids.get(i);
      }
    }
    return id;
  }

  private ColumnProjectionUtils() {
    // prevent instantiation
  }

}
