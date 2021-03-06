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

package org.apache.beam.sdk.extensions.sql;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.extensions.sql.impl.BeamSqlEnv;
import org.apache.beam.sdk.extensions.sql.impl.rel.BeamSqlRelUtils;
import org.apache.beam.sdk.extensions.sql.impl.schema.BeamPCollectionTable;
import org.apache.beam.sdk.transforms.Combine;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.PInput;
import org.apache.beam.sdk.values.PValue;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.values.TupleTag;

/**
 * {@link SqlTransform} is the DSL interface of Beam SQL. It translates a SQL query as a {@link
 * PTransform}, so developers can use standard SQL queries in a Beam pipeline.
 *
 * <h1>Beam SQL DSL usage:</h1>
 *
 * <p>A typical pipeline with Beam SQL DSL is:
 *
 * <pre>{@code
 * PipelineOptions options = PipelineOptionsFactory.create();
 * Pipeline p = Pipeline.create(options);
 *
 * //create table from TextIO;
 * PCollection<Row> inputTableA = p.apply(TextIO.read().from("/my/input/patha")).apply(...);
 * PCollection<Row> inputTableB = p.apply(TextIO.read().from("/my/input/pathb")).apply(...);
 *
 * //run a simple query, and register the output as a table in BeamSql;
 * String sql1 = "select MY_FUNC(c1), c2 from PCOLLECTION";
 * PCollection<Row> outputTableA = inputTableA.apply(
 *    SqlTransform
 *        .query(sql1)
 *        .registerUdf("MY_FUNC", MY_FUNC.class, "FUNC");
 *
 * //run a JOIN with one table from TextIO, and one table from another query
 * PCollection<Row> outputTableB =
 *     PCollectionTuple
 *     .of(new TupleTag<>("TABLE_O_A"), outputTableA)
 *     .and(new TupleTag<>("TABLE_B"), inputTableB)
 *         .apply(SqlTransform.query("select * from TABLE_O_A JOIN TABLE_B where ..."));
 *
 * //output the final result with TextIO
 * outputTableB.apply(...).apply(TextIO.write().to("/my/output/path"));
 *
 * p.run().waitUntilFinish();
 * }</pre>
 */
@AutoValue
@Experimental
public abstract class SqlTransform extends PTransform<PInput, PCollection<Row>> {
  static final String PCOLLECTION_NAME = "PCOLLECTION";

  abstract String queryString();

  abstract List<UdfDefinition> udfDefinitions();

  abstract List<UdafDefinition> udafDefinitions();

  abstract boolean autoUdfUdafLoad();

  @Override
  public PCollection<Row> expand(PInput input) {
    BeamSqlEnv sqlEnv = BeamSqlEnv.readOnly(PCOLLECTION_NAME, toTableMap(input));

    registerFunctions(sqlEnv);
    if (autoUdfUdafLoad()) {
      sqlEnv.loadUdfUdafFromProvider();
    }

    return BeamSqlRelUtils.toPCollection(input.getPipeline(), sqlEnv.parseQuery(queryString()));
  }

  @SuppressWarnings("unchecked")
  private Map<String, BeamSqlTable> toTableMap(PInput inputs) {
    /**
     * A single PCollection is transformed to a table named PCOLLECTION, other input types are
     * expanded and converted to tables using the tags as names.
     */
    if (inputs instanceof PCollection) {
      PCollection<?> pCollection = (PCollection<?>) inputs;
      return ImmutableMap.of(PCOLLECTION_NAME, new BeamPCollectionTable(pCollection));
    }

    ImmutableMap.Builder<String, BeamSqlTable> tables = ImmutableMap.builder();
    for (Map.Entry<TupleTag<?>, PValue> input : inputs.expand().entrySet()) {
      PCollection<?> pCollection = (PCollection<?>) input.getValue();
      tables.put(input.getKey().getId(), new BeamPCollectionTable(pCollection));
    }
    return tables.build();
  }

  private void registerFunctions(BeamSqlEnv sqlEnv) {
    udfDefinitions()
        .forEach(udf -> sqlEnv.registerUdf(udf.udfName(), udf.clazz(), udf.methodName()));

    udafDefinitions().forEach(udaf -> sqlEnv.registerUdaf(udaf.udafName(), udaf.combineFn()));
  }

  /**
   * Returns a {@link SqlTransform} representing an equivalent execution plan.
   *
   * <p>The {@link SqlTransform} can be applied to a {@link PCollection} or {@link PCollectionTuple}
   * representing all the input tables.
   *
   * <p>The {@link PTransform} outputs a {@link PCollection} of {@link Row}.
   *
   * <p>If the {@link PTransform} is applied to {@link PCollection} then it gets registered with
   * name <em>PCOLLECTION</em>.
   *
   * <p>If the {@link PTransform} is applied to {@link PCollectionTuple} then {@link
   * TupleTag#getId()} is used as the corresponding {@link PCollection}s name.
   *
   * <ul>
   *   <li>If the sql query only uses a subset of tables from the upstream {@link PCollectionTuple},
   *       this is valid;
   *   <li>If the sql query references a table not included in the upstream {@link
   *       PCollectionTuple}, an {@code IllegalStateException} is thrown during query validati on;
   *   <li>Always, tables from the upstream {@link PCollectionTuple} are only valid in the scope of
   *       the current query call.
   * </ul>
   */
  public static SqlTransform query(String queryString) {
    return builder()
        .setQueryString(queryString)
        .setUdafDefinitions(Collections.emptyList())
        .setUdfDefinitions(Collections.emptyList())
        .setAutoUdfUdafLoad(false)
        .build();
  }

  public SqlTransform withAutoUdfUdafLoad(boolean autoUdfUdafLoad) {
    return toBuilder().setAutoUdfUdafLoad(autoUdfUdafLoad).build();
  }
  /**
   * register a UDF function used in this query.
   *
   * <p>Refer to {@link BeamSqlUdf} for more about how to implement a UDF in BeamSql.
   */
  public SqlTransform registerUdf(String functionName, Class<? extends BeamSqlUdf> clazz) {
    return registerUdf(functionName, clazz, BeamSqlUdf.UDF_METHOD);
  }

  /**
   * Register {@link SerializableFunction} as a UDF function used in this query. Note, {@link
   * SerializableFunction} must have a constructor without arguments.
   */
  public SqlTransform registerUdf(String functionName, SerializableFunction sfn) {
    return registerUdf(functionName, sfn.getClass(), "apply");
  }

  private SqlTransform registerUdf(String functionName, Class<?> clazz, String method) {
    ImmutableList<UdfDefinition> newUdfDefinitions =
        ImmutableList.<UdfDefinition>builder()
            .addAll(udfDefinitions())
            .add(UdfDefinition.of(functionName, clazz, method))
            .build();

    return toBuilder().setUdfDefinitions(newUdfDefinitions).build();
  }

  /** register a {@link Combine.CombineFn} as UDAF function used in this query. */
  public SqlTransform registerUdaf(String functionName, Combine.CombineFn combineFn) {
    ImmutableList<UdafDefinition> newUdafs =
        ImmutableList.<UdafDefinition>builder()
            .addAll(udafDefinitions())
            .add(UdafDefinition.of(functionName, combineFn))
            .build();

    return toBuilder().setUdafDefinitions(newUdafs).build();
  }

  abstract Builder toBuilder();

  static Builder builder() {
    return new AutoValue_SqlTransform.Builder();
  }

  @AutoValue.Builder
  abstract static class Builder {
    abstract Builder setQueryString(String queryString);

    abstract Builder setUdfDefinitions(List<UdfDefinition> udfDefinitions);

    abstract Builder setUdafDefinitions(List<UdafDefinition> udafDefinitions);

    abstract Builder setAutoUdfUdafLoad(boolean autoUdfUdafLoad);

    abstract SqlTransform build();
  }

  @AutoValue
  abstract static class UdfDefinition {
    abstract String udfName();

    abstract Class<?> clazz();

    abstract String methodName();

    static UdfDefinition of(String udfName, Class<?> clazz, String methodName) {
      return new AutoValue_SqlTransform_UdfDefinition(udfName, clazz, methodName);
    }
  }

  @AutoValue
  abstract static class UdafDefinition {
    abstract String udafName();

    abstract Combine.CombineFn combineFn();

    static UdafDefinition of(String udafName, Combine.CombineFn combineFn) {
      return new AutoValue_SqlTransform_UdafDefinition(udafName, combineFn);
    }
  }
}
