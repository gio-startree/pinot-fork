/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.controller.api.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import org.apache.calcite.sql.SqlNode;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.helix.model.InstanceConfig;
import org.apache.pinot.common.Utils;
import org.apache.pinot.common.response.ProcessingException;
import org.apache.pinot.common.response.broker.BrokerResponseNative;
import org.apache.pinot.common.utils.DatabaseUtils;
import org.apache.pinot.common.utils.config.TagNameUtils;
import org.apache.pinot.common.utils.request.RequestUtils;
import org.apache.pinot.controller.ControllerConf;
import org.apache.pinot.controller.api.access.AccessControl;
import org.apache.pinot.controller.api.access.AccessControlFactory;
import org.apache.pinot.controller.api.access.AccessType;
import org.apache.pinot.controller.helix.core.PinotHelixResourceManager;
import org.apache.pinot.core.auth.Actions;
import org.apache.pinot.core.auth.ManualAuthorization;
import org.apache.pinot.core.query.executor.sql.SqlQueryExecutor;
import org.apache.pinot.query.QueryEnvironment;
import org.apache.pinot.query.parser.utils.ParserUtils;
import org.apache.pinot.spi.config.table.TableConfig;
import org.apache.pinot.spi.exception.DatabaseConflictException;
import org.apache.pinot.spi.exception.QueryErrorCode;
import org.apache.pinot.spi.exception.QueryErrorMessage;
import org.apache.pinot.spi.exception.QueryException;
import org.apache.pinot.spi.utils.CommonConstants;
import org.apache.pinot.spi.utils.CommonConstants.Broker.Request.QueryOptionKey;
import org.apache.pinot.spi.utils.JsonUtils;
import org.apache.pinot.spi.utils.builder.TableNameBuilder;
import org.apache.pinot.sql.parsers.CalciteSqlCompiler;
import org.apache.pinot.sql.parsers.CalciteSqlParser;
import org.apache.pinot.sql.parsers.PinotSqlType;
import org.apache.pinot.sql.parsers.SqlNodeAndOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Path("/")
public class PinotQueryResource {
  private static final Logger LOGGER = LoggerFactory.getLogger(PinotQueryResource.class);

  @Inject
  SqlQueryExecutor _sqlQueryExecutor;

  @Inject
  PinotHelixResourceManager _pinotHelixResourceManager;

  @Inject
  AccessControlFactory _accessControlFactory;

  @Inject
  ControllerConf _controllerConf;

  @POST
  @Path("sql")
  @ManualAuthorization // performed by broker
  public String handlePostSql(String requestJsonStr, @Context HttpHeaders httpHeaders) {
    try {
      JsonNode requestJson = JsonUtils.stringToJsonNode(requestJsonStr);
      if (!requestJson.has("sql")) {
        return constructQueryExceptionResponse(QueryErrorCode.JSON_PARSING,
            "JSON Payload is missing the query string field 'sql'");
      }
      String sqlQuery = requestJson.get("sql").asText();
      String traceEnabled = "false";
      if (requestJson.has("trace")) {
        traceEnabled = requestJson.get("trace").toString();
      }
      String queryOptions = null;
      if (requestJson.has("queryOptions")) {
        queryOptions = requestJson.get("queryOptions").asText();
      }
      LOGGER.debug("Trace: {}, Running query: {}", traceEnabled, sqlQuery);
      return executeSqlQuery(httpHeaders, sqlQuery, traceEnabled, queryOptions, "/sql");
    } catch (ProcessingException pe) {
      LOGGER.error("Caught exception while processing post request {}", pe.getMessage());
      return constructQueryExceptionResponse(QueryErrorCode.fromErrorCode(pe.getErrorCode()), pe.getMessage());
    } catch (QueryException ex) {
      LOGGER.error("Caught exception while processing post request {}", ex.getMessage());
      return constructQueryExceptionResponse(ex.getErrorCode(), ex.getMessage());
    } catch (WebApplicationException wae) {
      LOGGER.error("Caught exception while processing post request", wae);
      throw wae;
    } catch (Exception e) {
      LOGGER.error("Caught exception while processing post request", e);
      return constructQueryExceptionResponse(QueryErrorCode.INTERNAL, e.getMessage());
    }
  }

  @GET
  @Path("sql")
  @ManualAuthorization
  public String handleGetSql(@QueryParam("sql") String sqlQuery, @QueryParam("trace") String traceEnabled,
      @QueryParam("queryOptions") String queryOptions, @Context HttpHeaders httpHeaders) {
    try {
      LOGGER.debug("Trace: {}, Running query: {}", traceEnabled, sqlQuery);
      return executeSqlQuery(httpHeaders, sqlQuery, traceEnabled, queryOptions, "/sql");
    } catch (ProcessingException pe) {
      LOGGER.error("Caught exception while processing get request {}", pe.getMessage());
      return constructQueryExceptionResponse(QueryErrorCode.fromErrorCode(pe.getErrorCode()), pe.getMessage());
    } catch (QueryException ex) {
      LOGGER.warn("Caught exception while processing get request {}", ex.getMessage());
      return constructQueryExceptionResponse(ex.getErrorCode(), ex.getMessage());
    } catch (WebApplicationException wae) {
      LOGGER.error("Caught exception while processing get request", wae);
      throw wae;
    } catch (Exception e) {
      LOGGER.error("Caught exception while processing get request", e);
      return constructQueryExceptionResponse(QueryErrorCode.INTERNAL, e.getMessage());
    }
  }

  private String executeSqlQuery(@Context HttpHeaders httpHeaders, String sqlQuery, String traceEnabled,
      @Nullable String queryOptions, String endpointUrl)
      throws Exception {
    SqlNodeAndOptions sqlNodeAndOptions;
    sqlNodeAndOptions = CalciteSqlParser.compileToSqlNodeAndOptions(sqlQuery);
    Map<String, String> options = sqlNodeAndOptions.getOptions();
    if (queryOptions != null) {
      Map<String, String> optionsFromString = RequestUtils.getOptionsFromString(queryOptions);
      sqlNodeAndOptions.setExtraOptions(optionsFromString);
    }

    // Determine which engine to used based on query options.
    if (Boolean.parseBoolean(options.get(QueryOptionKey.USE_MULTISTAGE_ENGINE))) {
      if (_controllerConf.getProperty(CommonConstants.Helix.CONFIG_OF_MULTI_STAGE_ENGINE_ENABLED,
          CommonConstants.Helix.DEFAULT_MULTI_STAGE_ENGINE_ENABLED)) {
        return getMultiStageQueryResponse(sqlQuery, queryOptions, httpHeaders, endpointUrl, traceEnabled);
      } else {
        throw QueryErrorCode.INTERNAL.asException("V2 Multi-Stage query engine not enabled.");
      }
    } else {
      PinotSqlType sqlType = sqlNodeAndOptions.getSqlType();
      switch (sqlType) {
        case DQL:
          return getQueryResponse(sqlQuery, sqlNodeAndOptions.getSqlNode(), traceEnabled, queryOptions, httpHeaders);
        case DML:
          Map<String, String> headers =
              httpHeaders.getRequestHeaders().entrySet().stream().filter(entry -> !entry.getValue().isEmpty())
                  .map(entry -> Pair.of(entry.getKey(), entry.getValue().get(0)))
                  .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
          return _sqlQueryExecutor.executeDMLStatement(sqlNodeAndOptions, headers).toJsonString();
        default:
          throw QueryErrorCode.INTERNAL.asException("Unsupported SQL type - " + sqlType);
      }
    }
  }

  private String getMultiStageQueryResponse(String query, String queryOptions, HttpHeaders httpHeaders,
      String endpointUrl, String traceEnabled) {

    // Validate data access
    // we don't have a cross table access control rule so only ADMIN can make request to multi-stage engine.
    AccessControl accessControl = _accessControlFactory.create();
    if (!accessControl.hasAccess(AccessType.READ, httpHeaders, endpointUrl)) {
      throw new WebApplicationException("Permission denied", Response.Status.FORBIDDEN);
    }

    Map<String, String> queryOptionsMap = RequestUtils.parseQuery(query).getOptions();
    if (queryOptions != null) {
      queryOptionsMap.putAll(RequestUtils.getOptionsFromString(queryOptions));
    }
    String database = DatabaseUtils.extractDatabaseFromQueryRequest(queryOptionsMap, httpHeaders);
    QueryEnvironment queryEnvironment =
        new QueryEnvironment(database, _pinotHelixResourceManager.getTableCache(), null);
    List<String> tableNames;
    try {
      tableNames = queryEnvironment.getTableNamesForQuery(query);
    } catch (Exception e) {
      throw QueryErrorCode.SQL_PARSING.asException("Unable to find table for this query", e);
    }
    List<String> instanceIds;
    if (!tableNames.isEmpty()) {
      List<TableConfig> tableConfigList = getListTableConfigs(tableNames, database);
      if (tableConfigList == null || tableConfigList.isEmpty()) {
        throw QueryErrorCode.TABLE_DOES_NOT_EXIST.asException("Unable to find table in cluster, table does not exist");
      }

      // find the unions of all the broker tenant tags of the queried tables.
      Set<String> brokerTenantsUnion = getBrokerTenantsUnion(tableConfigList);
      if (brokerTenantsUnion.isEmpty()) {
        throw QueryErrorCode.BROKER_REQUEST_SEND.asException(
            String.format("Unable to dispatch multistage query for tables: [%s]", tableNames));
      }
      instanceIds = findCommonBrokerInstances(brokerTenantsUnion);
      if (instanceIds.isEmpty()) {
        // No common broker found for table tenants
        LOGGER.error("Unable to find a common broker instance for table tenants. Tables: {}, Tenants: {}", tableNames,
            brokerTenantsUnion);
        throw QueryErrorCode.BROKER_RESOURCE_MISSING.asException(
            "Unable to find a common broker instance for table tenants. Tables: " + tableNames + ", Tenants: "
                + brokerTenantsUnion);
      }
    } else {
      // TODO fail these queries going forward. Added this logic to take care of tautologies like BETWEEN 0 and -1.
      instanceIds = _pinotHelixResourceManager.getAllBrokerInstances();
      LOGGER.error("Unable to find table name from SQL {} thus dispatching to random broker.", query);
    }
    String instanceId = selectRandomInstanceId(instanceIds);
    return sendRequestToBroker(query, instanceId, traceEnabled, queryOptions, httpHeaders);
  }

  private String getQueryResponse(String query, @Nullable SqlNode sqlNode, String traceEnabled, String queryOptions,
      HttpHeaders httpHeaders) {
    // Get resource table name.
    String tableName;
    Map<String, String> queryOptionsMap = RequestUtils.parseQuery(query).getOptions();
    if (queryOptions != null) {
      queryOptionsMap.putAll(RequestUtils.getOptionsFromString(queryOptions));
    }
    String database;
    try {
      database = DatabaseUtils.extractDatabaseFromQueryRequest(queryOptionsMap, httpHeaders);
    } catch (DatabaseConflictException e) {
      throw QueryErrorCode.QUERY_VALIDATION.asException(e);
    }
    try {
      String inputTableName =
          sqlNode != null ? RequestUtils.getTableNames(CalciteSqlParser.compileSqlNodeToPinotQuery(sqlNode)).iterator()
              .next() : CalciteSqlCompiler.compileToBrokerRequest(query).getQuerySource().getTableName();
      tableName = _pinotHelixResourceManager.getActualTableName(inputTableName, database);
    } catch (Exception e) {
      LOGGER.error("Caught exception while compiling query: {}", query, e);

      // Check if the query is a v2 supported query
      if (ParserUtils.canCompileWithMultiStageEngine(query, database, _pinotHelixResourceManager.getTableCache())) {
        throw QueryErrorCode.SQL_PARSING.asException(
            "It seems that the query is only supported by the multi-stage query engine, please retry the query using "
                + "the multi-stage query engine "
                + "(https://docs.pinot.apache.org/developers/advanced/v2-multi-stage-query-engine)");
      } else {
        throw QueryErrorCode.SQL_PARSING.asException(e);
      }
    }
    String rawTableName = TableNameBuilder.extractRawTableName(tableName);

    // Validate data access
    AccessControl accessControl = _accessControlFactory.create();
    if (!accessControl.hasAccess(rawTableName, AccessType.READ, httpHeaders, Actions.Table.QUERY)) {
      throw QueryErrorCode.ACCESS_DENIED.asException();
    }

    // Get brokers for the resource table.
    List<String> instanceIds = _pinotHelixResourceManager.getBrokerInstancesFor(rawTableName);
    String instanceId = selectRandomInstanceId(instanceIds);
    return sendRequestToBroker(query, instanceId, traceEnabled, queryOptions, httpHeaders);
  }

  // given a list of tables, returns the list of tableConfigs
  private List<TableConfig> getListTableConfigs(List<String> tableNames, String database) {
    List<TableConfig> allTableConfigList = new ArrayList<>();
    for (String tableName : tableNames) {
      String actualTableName = _pinotHelixResourceManager.getActualTableName(tableName, database);
      List<TableConfig> tableConfigList = new ArrayList<>();
      if (_pinotHelixResourceManager.hasRealtimeTable(actualTableName)) {
        tableConfigList.add(Objects.requireNonNull(_pinotHelixResourceManager.getRealtimeTableConfig(actualTableName)));
      }
      if (_pinotHelixResourceManager.hasOfflineTable(actualTableName)) {
        tableConfigList.add(Objects.requireNonNull(_pinotHelixResourceManager.getOfflineTableConfig(actualTableName)));
      }
      if (tableConfigList.isEmpty()) {
        return null;
      }
      allTableConfigList.addAll(tableConfigList);
    }
    return allTableConfigList;
  }

  private String selectRandomInstanceId(List<String> instanceIds) {
    if (instanceIds.isEmpty()) {
      throw QueryErrorCode.BROKER_RESOURCE_MISSING.asException("No broker found for query");
    }

    instanceIds.retainAll(_pinotHelixResourceManager.getOnlineInstanceList());
    if (instanceIds.isEmpty()) {
      throw QueryErrorCode.BROKER_INSTANCE_MISSING.asException("No online broker found for query");
    }

    // Send query to a random broker.
    return instanceIds.get(ThreadLocalRandom.current().nextInt(instanceIds.size()));
  }

  private List<String> findCommonBrokerInstances(Set<String> brokerTenants) {
    Stream<InstanceConfig> brokerInstanceConfigs = _pinotHelixResourceManager.getAllBrokerInstanceConfigs().stream();
    for (String brokerTenant : brokerTenants) {
      brokerInstanceConfigs = brokerInstanceConfigs.filter(
          instanceConfig -> instanceConfig.containsTag(TagNameUtils.getBrokerTagForTenant(brokerTenant)));
    }
    return brokerInstanceConfigs.map(InstanceConfig::getInstanceName).collect(Collectors.toList());
  }

  // return the union of brokerTenants from the tables list.
  private Set<String> getBrokerTenantsUnion(List<TableConfig> tableConfigList) {
    Set<String> tableBrokerTenants = new HashSet<>();
    for (TableConfig tableConfig : tableConfigList) {
      tableBrokerTenants.add(tableConfig.getTenantConfig().getBroker());
    }
    return tableBrokerTenants;
  }

  private String sendRequestToBroker(String query, String instanceId, String traceEnabled, String queryOptions,
      HttpHeaders httpHeaders) {
    InstanceConfig instanceConfig = _pinotHelixResourceManager.getHelixInstanceConfig(instanceId);
    if (instanceConfig == null) {
      LOGGER.error("Instance {} not found", instanceId);
      throw QueryErrorCode.INTERNAL.asException();
    }

    String hostName = instanceConfig.getHostName();
    // Backward-compatible with legacy hostname of format 'Broker_<hostname>'
    if (hostName.startsWith(CommonConstants.Helix.PREFIX_OF_BROKER_INSTANCE)) {
      hostName = hostName.substring(CommonConstants.Helix.BROKER_INSTANCE_PREFIX_LENGTH);
    }

    String protocol = _controllerConf.getControllerBrokerProtocol();
    int port = _controllerConf.getControllerBrokerPortOverride() > 0 ? _controllerConf.getControllerBrokerPortOverride()
        : Integer.parseInt(instanceConfig.getPort());
    String url = getQueryURL(protocol, hostName, port);
    ObjectNode requestJson = getRequestJson(query, traceEnabled, queryOptions);

    // forward client-supplied headers
    Map<String, String> headers =
        httpHeaders.getRequestHeaders().entrySet().stream().filter(entry -> !entry.getValue().isEmpty())
            .map(entry -> Pair.of(entry.getKey(), entry.getValue().get(0)))
            .collect(Collectors.toMap(Pair::getKey, Pair::getValue));

    return sendRequestRaw(url, query, requestJson, headers);
  }

  private ObjectNode getRequestJson(String query, String traceEnabled, String queryOptions) {
    ObjectNode requestJson = JsonUtils.newObjectNode();
    requestJson.put("sql", query);
    if (traceEnabled != null && !traceEnabled.isEmpty()) {
      requestJson.put("trace", traceEnabled);
    }
    if (queryOptions != null && !queryOptions.isEmpty()) {
      requestJson.put("queryOptions", queryOptions);
    }
    return requestJson;
  }

  private String getQueryURL(String protocol, String hostName, int port) {
    return String.format("%s://%s:%d/query/sql", protocol, hostName, port);
  }

  public String sendPostRaw(String urlStr, String requestStr, Map<String, String> headers) {
    HttpURLConnection conn = null;
    try {
      /*if (LOG.isInfoEnabled()){
        LOGGER.info("Sending a post request to the server - " + urlStr);
      }

      if (LOG.isDebugEnabled()){
        LOGGER.debug("The request is - " + requestStr);
      }*/

      LOGGER.info("url string passed is : {}", urlStr);
      final URL url = new URL(urlStr);
      conn = (HttpURLConnection) url.openConnection();
      conn.setDoOutput(true);
      conn.setRequestMethod("POST");
      // conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

      conn.setRequestProperty("Accept-Encoding", "gzip");

      final String string = requestStr;
      final byte[] requestBytes = string.getBytes(StandardCharsets.UTF_8);
      conn.setRequestProperty("Content-Length", String.valueOf(requestBytes.length));
      conn.setRequestProperty("http.keepAlive", String.valueOf(true));
      conn.setRequestProperty("default", String.valueOf(true));

      if (headers != null && !headers.isEmpty()) {
        final Set<Entry<String, String>> entries = headers.entrySet();
        for (final Entry<String, String> entry : entries) {
          conn.setRequestProperty(entry.getKey(), entry.getValue());
        }
      }

      //GZIPOutputStream zippedOutputStream = new GZIPOutputStream(conn.getOutputStream());
      final OutputStream os = new BufferedOutputStream(conn.getOutputStream());
      os.write(requestBytes);
      os.flush();
      os.close();
      final int responseCode = conn.getResponseCode();

      /*if (LOG.isInfoEnabled()){
        LOGGER.info("The http response code is " + responseCode);
      }*/
      if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
        throw new WebApplicationException("Permission denied", Response.Status.FORBIDDEN);
      } else if (responseCode != HttpURLConnection.HTTP_OK) {
        InputStream errorStream = conn.getErrorStream();
        throw new IOException(
            "Failed : HTTP error code : " + responseCode + ". Root Cause: " + (errorStream != null ? IOUtils.toString(
                errorStream, StandardCharsets.UTF_8) : "Unknown"));
      }
      final byte[] bytes = drain(new BufferedInputStream(conn.getInputStream()));

      final String output = new String(bytes, StandardCharsets.UTF_8);
      /*if (LOG.isDebugEnabled()){
        LOGGER.debug("The response from the server is - " + output);
      }*/
      return output;
    } catch (final Exception ex) {
      LOGGER.error("Caught exception while sending query request", ex);
      Utils.rethrowException(ex);
      throw new AssertionError("Should not reach this");
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  byte[] drain(InputStream inputStream)
      throws IOException {
    try {
      final byte[] buf = new byte[1024];
      int len;
      final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      while ((len = inputStream.read(buf)) > 0) {
        byteArrayOutputStream.write(buf, 0, len);
      }
      return byteArrayOutputStream.toByteArray();
    } finally {
      inputStream.close();
    }
  }

  public String sendRequestRaw(String url, String query, ObjectNode requestJson, Map<String, String> headers) {
    try {
      final long startTime = System.currentTimeMillis();
      final String pinotResultString = sendPostRaw(url, requestJson.toString(), headers);

      final long queryTime = System.currentTimeMillis() - startTime;
      LOGGER.info("Query: {} Time: {}", query, queryTime);

      return pinotResultString;
    } catch (final Exception ex) {
      LOGGER.error("Caught exception in sendQueryRaw", ex);
      Utils.rethrowException(ex);
      throw new AssertionError("Should not reach this");
    }
  }

  private static String constructQueryExceptionResponse(QueryErrorCode errorCode, String message) {
    return constructQueryExceptionResponse(new QueryErrorMessage(errorCode, message, message));
  }

  private static String constructQueryExceptionResponse(QueryErrorMessage message) {
    try {
      return new BrokerResponseNative(message).toJsonString();
    } catch (IOException ioe) {
      Utils.rethrowException(ioe);
      throw new AssertionError("Should not reach this");
    }
  }
}
