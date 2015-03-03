/*   Copyright (C) 2013-2014 Computer Sciences Corporation
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
 * limitations under the License. */

package ezbake.data.elastic;

import static ezbake.data.elastic.common.ElasticUtils.PERCOLATOR_TYPE;
import static ezbake.data.elastic.common.ElasticUtils.VISIBILITY_FIELD;
import static ezbake.data.elastic.common.ElasticUtils.getVisibilityFilter;
import static ezbake.util.AuditEvent.event;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang.StringUtils;
import org.apache.thrift.TException;
import org.apache.thrift.TProcessor;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeBuilder;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.simple.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.collect.ImmutableSet;

import ezbake.base.thrift.Authorizations;
import ezbake.base.thrift.CancelStatus;
import ezbake.base.thrift.EzSecurityToken;
import ezbake.base.thrift.EzSecurityTokenException;
import ezbake.base.thrift.Permission;
import ezbake.base.thrift.TokenType;
import ezbake.base.thrift.Visibility;
import ezbake.data.base.EzbakeBaseDataService;
import ezbake.data.base.thrift.PurgeItems;
import ezbake.data.base.thrift.PurgeOptions;
import ezbake.data.base.thrift.PurgeResult;
import ezbake.data.common.TokenUtils;
import ezbake.data.elastic.common.ElasticUtils;
import ezbake.data.elastic.common.VisibilityFilterConfig;
import ezbake.data.elastic.thrift.Document;
import ezbake.data.elastic.thrift.DocumentIdentifier;
import ezbake.data.elastic.thrift.DocumentIndexingException;
import ezbake.data.elastic.thrift.EzElastic;
import ezbake.data.elastic.thrift.FieldsNotFound;
import ezbake.data.elastic.thrift.IndexResponse;
import ezbake.data.elastic.thrift.MalformedQueryException;
import ezbake.data.elastic.thrift.PercolateQuery;
import ezbake.data.elastic.thrift.Query;
import ezbake.data.elastic.thrift.SearchResult;
import ezbake.data.elastic.thrift.UpdateOptions;
import ezbake.data.elastic.thrift.UpdateScript;
import ezbake.security.permissions.PermissionUtils;
import ezbake.util.AuditEvent;
import ezbake.util.AuditEventType;

import ezbakehelpers.ezconfigurationhelpers.application.EzBakeApplicationConfigurationHelper;
import ezbakehelpers.ezconfigurationhelpers.elasticsearch.ElasticsearchConfigurationHelper;

//TODO properly audit log the public methods redmine issue #7765
public final class EzElasticHandler extends EzbakeBaseDataService implements EzElastic.Iface {
    public static final Document BLANK_DOCUMENT = new Document();

    private static final Logger logger = LoggerFactory.getLogger(EzElasticHandler.class);

    private static final String INDEX_VERSION_KEY = "ezelastic.index.version";
    private static final String USE_LOCAL_ELASTIC_KEY = "ezelastic.use.local.elastic";
    private static final String POST_FILTER_PERCOLATE_QUERIES = "ezelastic.post.filter.percolate.queries";
    private static final int DEFAULT_INDEX_VERSION = 1;

    // Metrics
    private static final String PUT_TIMER_NAME = MetricRegistry.name(EzElasticHandler.class, "PUT");
    private static final String PURGE_TIMER_NAME = MetricRegistry.name(EzElasticHandler.class, "PURGE");
    private static final String PERCOLATE_TIMER_NAME = MetricRegistry.name(EzElasticHandler.class, "PERCOLATE");
    private static final String GET_METER_NAME = MetricRegistry.name(EzElasticHandler.class, "GET");
    private static final String QUERY_METER_NAME = MetricRegistry.name(EzElasticHandler.class, "QUERY");

    static {
        BLANK_DOCUMENT.setVisibility(new Visibility());
        BLANK_DOCUMENT.set_jsonObject(StringUtils.EMPTY);
        BLANK_DOCUMENT.set_type(StringUtils.EMPTY);
    }

    private DocumentStore documentStore;
    private String applicationName = StringUtils.EMPTY;
    private static boolean postFilterPercolateQueries;

    public void init() {
        final Properties config = getConfigurationProperties();
        final EzBakeApplicationConfigurationHelper appConfig = new EzBakeApplicationConfigurationHelper(config);
        final ElasticsearchConfigurationHelper elasticConfig = new ElasticsearchConfigurationHelper(config);

        applicationName = appConfig.getApplicationName().toLowerCase();
        final String elasticHost = elasticConfig.getElasticsearchHost();
        final String elasticCluster = elasticConfig.getElasticsearchClusterName();
        final int elasticPort = elasticConfig.getElasticsearchPort();
        final boolean elasticForceRefresh = elasticConfig.getForceRefresh();

        final String elasticIndexVersionString = config.getProperty(INDEX_VERSION_KEY);
        final String elasticUseLocalString = config.getProperty(USE_LOCAL_ELASTIC_KEY);

        final String postFilterPercolateQueriesString = config.getProperty(POST_FILTER_PERCOLATE_QUERIES,"true");
        if(postFilterPercolateQueriesString.equals("false"))
            postFilterPercolateQueries = false;
        else
            postFilterPercolateQueries = true;

        final int elasticIndexVersion =
                elasticIndexVersionString == null ? DEFAULT_INDEX_VERSION : Integer.parseInt(elasticIndexVersionString);

        if (elasticUseLocalString != null && "true".equalsIgnoreCase(elasticUseLocalString)) {
            documentStore = startLocalNode(elasticCluster, elasticForceRefresh, elasticIndexVersion);
        } else {
            documentStore = ElasticClient.getInstance(
                    elasticHost, elasticPort, elasticCluster, applicationName, elasticForceRefresh,
                    elasticIndexVersion);
        }

        initAuditLogger(EzElasticHandler.class);
    }

    @Override
    public TProcessor getThriftProcessor() {
        init();
        return new EzElastic.Processor<EzElastic.Iface>(this);
    }

    @Override
    public boolean ping() {
        return documentStore.ping();
    }

    @Override
    public IndexResponse put(Document document, EzSecurityToken ezSecurityToken)
            throws TException, DocumentIndexingException {
        TokenUtils.validateSecurityToken(ezSecurityToken, getConfigurationProperties());
        try (Timer.Context context = getMetricRegistry().timer(PUT_TIMER_NAME).time()) {
            return bulkPut(Collections.singletonList(document), ezSecurityToken).get(0);
        }
    }

    @Override
    public IndexResponse update(
            DocumentIdentifier id, UpdateScript script, UpdateOptions options, EzSecurityToken userToken)
            throws TException {
        auditLog(userToken, AuditEventType.FileObjectModify, "update", id.getType(), id.getId());
        TokenUtils.validateSecurityToken(userToken, getConfigurationProperties());

        final String key = MetricRegistry.name(EzElasticHandler.class, "UPDATE", id.getId());
        retrieveCounter(key).inc();

        return documentStore.update(id, script, options, userToken);
    }

    @Override
    public Document get(String _id, EzSecurityToken userToken) throws TException {
        TokenUtils.validateSecurityToken(userToken, getConfigurationProperties());

        // handle the meter and counter for Metrics
        getMetricRegistry().meter(GET_METER_NAME).mark();
        final String key = MetricRegistry.name(EzElasticHandler.class, "GET", _id);
        retrieveCounter(key).inc();

        return getWithType(_id, StringUtils.EMPTY, userToken);
    }

    @Override
    public Document getWithType(String _id, String _type, EzSecurityToken userToken) throws TException {
        final List<Document> results = bulkGetWithType(ImmutableSet.of(_id), _type, userToken);
        if (results.isEmpty()) {
            // If we got back 0 records from the multi get that means our doc
            // doesn't exist
            return BLANK_DOCUMENT;
        }
        return results.get(0);
    }

    @Override
    public Document getWithFields(String _id, String _type, Set<String> fields, EzSecurityToken userToken)
            throws FieldsNotFound, TException {
        auditLog(userToken, AuditEventType.FileObjectAccess, "getWithFields", _type, _id);
        TokenUtils.validateSecurityToken(userToken, getConfigurationProperties());
        try {
            return documentStore.get(_id, _type, fields, userToken);
        } catch (final MalformedQueryException e) {
            logger.error(
                    "Malformed query when executing get with fields which "
                            + "simply creates a basic id query based on the supplied id. This shouldn't happen...", e);

            return BLANK_DOCUMENT;
        }
    }

    @Override
    public SearchResult query(Query query, EzSecurityToken userToken) throws TException, MalformedQueryException {

        final String type = query.isSetType() ? query.getType() : "";
        auditLog(userToken, AuditEventType.FileObjectAccess, "query", type, query.getSearchString());
        TokenUtils.validateSecurityToken(userToken, getConfigurationProperties());

        // TODO: we could add a timer metric to calculate how long the query takes; meter will
        // keep track of how many calls per time period
        getMetricRegistry().meter(QUERY_METER_NAME).mark();

        return documentStore.get(
                query.getSearchString(), type, query.getSortCriteria(), query.getReturnedFields(), query.getFacets(),
                query.getFilterJson(), query.isSetPage() ? query.getPage().getOffset() : 0,
                query.isSetPage() ? query.getPage().getPageSize() : (short) -1, query.getHighlighting(), userToken);
    }

    @Override
    public void deleteById(String _id, EzSecurityToken userToken) throws TException {
        deleteWithType(_id, StringUtils.EMPTY, userToken);
    }

    @Override
    public void deleteWithType(String _id, String _type, EzSecurityToken userToken) throws TException {
        bulkDeleteWithType(ImmutableSet.of(_id), _type, userToken);
    }

    @Override
    public void deleteByQuery(String query, EzSecurityToken userToken) throws TException {
        deleteByQueryWithType(query, StringUtils.EMPTY, userToken);
    }

    @Override
    public void deleteByQueryWithType(String query, String _type, EzSecurityToken userToken) throws TException {
        auditLog(userToken, AuditEventType.FileObjectDelete, "delete", _type, query);
        TokenUtils.validateSecurityToken(userToken, getConfigurationProperties());
        documentStore.delete(query, _type, userToken);
    }

    @Override
    public List<IndexResponse> bulkPut(List<Document> documents, EzSecurityToken securityToken)
            throws TException, DocumentIndexingException {
        auditLog(securityToken, AuditEventType.FileObjectCreate, "bulkPut", "", "");
        TokenUtils.validateSecurityToken(securityToken, getConfigurationProperties());
        // Assign a document id if one does not exist
        for (final Document document : documents) {
            if (StringUtils.isBlank(document.get_id())) {
                document.set_id(UUID.randomUUID().toString());
            }
            auditLog(
                    securityToken, AuditEventType.FileObjectCreate, "indexing", document.get_type(), document.get_id());
        }

        return documentStore.put(documents);
    }

    @Override
    public List<Document> bulkGetWithType(Set<String> ids, String _type, EzSecurityToken userToken) throws TException {
        auditLog(userToken, AuditEventType.FileObjectAccess, "get", _type, StringUtils.join(ids, ", "));
        TokenUtils.validateSecurityToken(userToken, getConfigurationProperties());
        try {
            return documentStore.get(ids, _type, userToken);
        } catch (final MalformedQueryException e) {
            throw new TException(e.getMessage());
        }
    }

    @Override
    public void bulkDelete(Set<String> ids, EzSecurityToken userToken) throws TException {
        bulkDeleteWithType(ids, StringUtils.EMPTY, userToken);
    }

    @Override
    public void bulkDeleteWithType(Set<String> ids, String _type, EzSecurityToken userToken) throws TException {
        auditLog(userToken, AuditEventType.FileObjectDelete, "delete", _type, StringUtils.join(ids, ", "));
        TokenUtils.validateSecurityToken(userToken, getConfigurationProperties());
        documentStore.delete(ids, _type, userToken);
    }

    @Override
    public long countByQuery(String query, EzSecurityToken userToken) throws TException {
        return countByQueryAndType(new HashSet<String>(), query, userToken);
    }

    @Override
    public long countByType(Set<String> types, EzSecurityToken userToken) throws TException {
        return countByQueryAndType(types, StringUtils.EMPTY, userToken);
    }

    @Override
    public long countByQueryAndType(Set<String> types, String query, EzSecurityToken userToken) throws TException {
        auditLog(userToken, AuditEventType.FileObjectAccess, "count", StringUtils.join(types, ", "), "N/A");
        TokenUtils.validateSecurityToken(userToken, getConfigurationProperties());
        return documentStore.count(types, query, new HashSet<String>(), userToken);
    }

    @Override
    public IndexResponse addPercolateQuery(PercolateQuery query, EzSecurityToken userToken) throws EzSecurityTokenException, TException {
        AuditEvent evt = event(AuditEventType.FileObjectCreate.getName(), userToken).arg("event", "addPercolateQuery");
        try {
            TokenUtils.validateSecurityToken(userToken, getConfigurationProperties());

            Authorizations authorizations;
            if(query.getAuthorizations()==null) {
                authorizations = userToken.getAuthorizations();
            } else{
                if(!ElasticUtils.verifyAuthorizations(query.getAuthorizations(),userToken.getAuthorizations())) {
                    throw new EzSecurityTokenException(
                            "Authorizations for the query are not contained within the token");
                }
                authorizations = query.getAuthorizations();
            }

            query = addVisibilityFilter(query,authorizations);
            final Document doc =
                    new Document(ElasticUtils.PERCOLATOR_TYPE, query.getVisibility(), query.getQueryDocument());

            if (StringUtils.isBlank(query.getId())) {
                query.setId(UUID.randomUUID().toString());
            }
            doc.set_id(query.getId());
            evt.arg("percolator id", query.getId());
            return put(doc, userToken);
        } catch (Exception e) {
            logError(e, evt, "addPercolateQuery encountered an exception:" + e.getMessage());
            throw e;
        } finally {
            auditLogger.logEvent(evt);
        }
    }

    @Override
    public boolean removePercolateQuery(String id, EzSecurityToken userToken) throws TException {
        AuditEvent evt =
                event(AuditEventType.FileObjectDelete.getName(), userToken).arg("event", "removePercolateQuery")
                        .arg("percolateId", id);

        try {
            TokenUtils.validateSecurityToken(userToken, getConfigurationProperties());

            // get the visibility via get api.
            Visibility documentVisibility = documentStore.getPercolatorVisibility(id);
            if (documentVisibility == null) {
                throw new TException("No percolator with id " + id + " found");
            }
            if (!canDelete(userToken, documentVisibility)) {
                throw new EzSecurityTokenException(
                        "User does not have the proper permissions to delete the percolator query with id: " + id);
            }

            try (Timer.Context context = getMetricRegistry().timer(PERCOLATE_TIMER_NAME).time()) {
                return documentStore.deletePercolator(id);
            }
        } catch (Exception e) {
            logError(e, evt, "removePercolateQuery encountered an exception:" + e.getMessage());
            throw e;
        } finally {
            auditLogger.logEvent(evt);
        }
    }

    @Override
    public List<PercolateQuery> percolate(List<Document> docs, EzSecurityToken userToken) throws TException {
        AuditEvent evt = event(AuditEventType.FileObjectAccess.getName(), userToken).arg("event", "percolate")
                .arg("docs", docs.size());
        try {
            TokenUtils.validateSecurityToken(userToken, getConfigurationProperties());
            for (Document doc : docs) {
                evt.arg("docId", doc.get_id());
            }
            try (Timer.Context context = getMetricRegistry().timer(PERCOLATE_TIMER_NAME).time()) {
                List<PercolateQuery> percolateQueries = documentStore.percolate(docs);

                if(userToken.getType()==TokenType.APP ||(!postFilterPercolateQueries)) {
                    return percolateQueries;
                }

                return visibilityFilterPercolateQueries(percolateQueries,userToken);
            }
        } catch (Exception e) {
            logError(e, evt, "percolate encountered an exception:" + e.getMessage());
            throw e;
        } finally {
            auditLogger.logEvent(evt);
        }
    }

    @Override
    public List<PercolateQuery> percolateByIds(List<String> ids, String type, int maxMatches, EzSecurityToken userToken)
            throws TException {
        AuditEvent evt = event(AuditEventType.FileObjectAccess.getName(), userToken).arg("event", "percolateByIds")
                .arg("docs", ids.size()).arg("docIds", ids);
        try {
            TokenUtils.validateSecurityToken(userToken, getConfigurationProperties());

            try (Timer.Context context = getMetricRegistry().timer(PERCOLATE_TIMER_NAME).time()) {
                List<PercolateQuery> percolateQueries = documentStore.percolateByIds(ids, type, maxMatches);

                if(userToken.getType()==TokenType.APP ||( !postFilterPercolateQueries)) {
                    return percolateQueries;
                }

                return visibilityFilterPercolateQueries(percolateQueries,userToken);
            }
        } catch (Exception e) {
            logError(e, evt, "percolateByIds encountered an exception:" + e.getMessage());
            throw e;
        } finally {
            auditLogger.logEvent(evt);
        }
    }

    @Override
    public SearchResult queryPercolate(Query query, EzSecurityToken userToken)
            throws MalformedQueryException, TException {
        AuditEvent evt = event(AuditEventType.FileObjectAccess.getName(), userToken).arg("event", "queryPercolate")
                .arg("queryPercolate", query);
        try {
            TokenUtils.validateSecurityToken(userToken, getConfigurationProperties());
            return query(query, userToken);
        } catch (Exception e) {
            logError(e, evt, "queryPercolate encountered an exception:" + e.getMessage());
            throw e;
        } finally {
            auditLogger.logEvent(evt);
        }
    }

    @Override
    public void openIndex(EzSecurityToken userToken) throws TException {
        auditLog(userToken, AuditEventType.FileObjectCreate, "openIndex", "", "N/A");
        TokenUtils.validateSecurityToken(userToken, getConfigurationProperties());
        documentStore.openIndex();
    }

    @Override
    public void closeIndex(EzSecurityToken userToken) throws TException {
        auditLog(userToken, AuditEventType.FileObjectDelete, "closeIndex", "", "N/A");
        TokenUtils.validateSecurityToken(userToken, getConfigurationProperties());
        documentStore.closeIndex();
    }

    @Override
    public void applySettings(String settingsJson, EzSecurityToken userToken) throws TException {
        auditLog(userToken, AuditEventType.FileObjectPermissionModifications, "applySettings", settingsJson, "N/A");
        TokenUtils.validateSecurityToken(userToken, getConfigurationProperties());

        documentStore.applySettings(settingsJson);
    }

    @Override
    public void setTypeMapping(String type, String mappingJson, EzSecurityToken userToken) throws TException {
        auditLog(userToken, AuditEventType.FileObjectPermissionModifications, "setTypeMapping", type, "N/A");
        TokenUtils.validateSecurityToken(userToken, getConfigurationProperties());
        documentStore.setTypeMapping(type, mappingJson);
    }

    @Override
    public void forceIndexRefresh(EzSecurityToken userToken) throws TException {
        auditLog(userToken, AuditEventType.FileObjectAccess, "forceIndexRefresh", "", "N/A");
        TokenUtils.validateSecurityToken(userToken, getConfigurationProperties());
        documentStore.forceIndexRefresh();
    }

    @Override
    public PurgeResult purge(PurgeItems items, PurgeOptions options, EzSecurityToken token) throws TException {
        auditLog(token, AuditEventType.FileObjectDelete, "purge", "", "N/A");
        TokenUtils.validateSecurityToken(token, getConfigurationProperties());
        try (Timer.Context context = getMetricRegistry().timer(PURGE_TIMER_NAME).time()) {
            return documentStore.purge(items.getPurgeId(), items.getItems(), options.getBatchSize());
        }
    }

    @Override
    public CancelStatus cancelPurge(long purgeId, EzSecurityToken token) throws TException {
        auditLog(token, AuditEventType.FileObjectDelete, "cancelPurge", "", "N/A");
        TokenUtils.validateSecurityToken(token, getConfigurationProperties());
        return documentStore.cancelPurge(purgeId);
    }

    private void auditLog(EzSecurityToken userToken, AuditEventType eventType, String action, String type, String uri) {
        final AuditEvent event = new AuditEvent(eventType, userToken).arg(
                "security app",
                userToken.isSetValidity() ? userToken.getValidity().getIssuedTo() : "N/A - No Application supplied")
                .arg(
                        "user", userToken.isSetTokenPrincipal() ? userToken.getTokenPrincipal().getPrincipal() :
                                "N/A - Service Request").arg("action", action).arg("application", applicationName)
                .arg("type", type).arg("uri", uri);
        logEvent(event);
    }

    /**
     * Retrieve the MetricRegistry counter associated with the key parameter
     *
     * @param name - the key into MetricRegistry's counter map
     * @return Counter
     */
    private Counter retrieveCounter(String name) {
        Counter c = null;

        final Map<String, Counter> counters = getMetricRegistry().getCounters();
        if (counters.containsKey(name)) {
            c = counters.get(name);
        } else {
            c = getMetricRegistry().counter(name);
        }

        return c;
    }

    private ElasticClient startLocalNode(String clusterName, boolean refresh, int version) {
        final ImmutableSettings.Builder builder = ImmutableSettings.settingsBuilder()
                .put("node.name", applicationName + "-local-node" + System.currentTimeMillis()).put("node.data", true)
                .put("cluster.name", clusterName).put("index.store.type", "memory")
                .put("index.store.fs.memory.enabled", "true").put("gateway.type", "none")
                .put("path.data", "./local-cluster/" + clusterName + "/data")
                .put("path.work", "./local-cluster/" + clusterName + "/work")
                .put("path.logs", "./local-cluster/" + clusterName + "/logs").put("index.number_of_shards", "1")
                .put("index.number_of_replicas", "0").put("cluster.routing.schedule", "50ms");
        final Node localNode = NodeBuilder.nodeBuilder().settings(builder).node();

        final Client client = localNode.client();
        client.admin().cluster().prepareHealth().setWaitForYellowStatus().setTimeout(TimeValue.timeValueMinutes(1))
                .execute().actionGet();
        return new ElasticClient(client, applicationName, refresh, version);
    }

    private void logError(Exception e, AuditEvent evt, String loggerMessage) {
        evt.failed();
        e.printStackTrace();
        evt.arg(e.getClass().getName(), e);
        logger.error(loggerMessage);
    }

    private boolean canDelete(EzSecurityToken userToken, Visibility docVisibility) {
        final Authorizations userAuths = userToken.getAuthorizations();
        final Set<Permission> userPerms = PermissionUtils.getPermissions(userAuths, docVisibility);
        return userPerms.contains(Permission.WRITE);
    }

    private PercolateQuery addVisibilityFilter(PercolateQuery query,EzSecurityToken userToken) throws TException {
        return addVisibilityFilter( query, userToken.getAuthorizations());
    }
    private PercolateQuery addVisibilityFilter(PercolateQuery query,Authorizations authorizations) throws TException {
        // Set visibility filter
        final VisibilityFilterConfig filterConfig =
                new VisibilityFilterConfig(VISIBILITY_FIELD, EnumSet.of(Permission.READ));
        FilterBuilder visibilityFilter = getVisibilityFilter(authorizations, filterConfig);
        JSONObject visibilityJSON = new JSONObject(visibilityFilter.toString());

        JSONObject filteredToAdd = new JSONObject();

        PercolateQuery queryWithVisibility = query;
        JSONObject outer = new JSONObject (query.getQueryDocument());
        JSONObject gettingOuterQuery = outer.getJSONObject("query");

        try{
            JSONObject gettingFiltered = gettingOuterQuery.getJSONObject("filtered");
            JSONObject gettingFilters = gettingFiltered.getJSONObject("filter");

            JSONArray filters = new JSONArray();
            filters.add(gettingFilters);
            filters.add(visibilityJSON);

            JSONObject andingFilters = new JSONObject();
            andingFilters.put("and",filters);

            filteredToAdd.put("filter", andingFilters);
            filteredToAdd.put("query",gettingFiltered.getJSONObject("query"));
        } catch(JSONException e) {
            filteredToAdd.put("query",gettingOuterQuery);
            filteredToAdd.put("filter",visibilityJSON);
        }

        JSONObject queryToAdd = new JSONObject();
        queryToAdd.put("filtered",filteredToAdd);

        outer.put("query",queryToAdd);
        queryWithVisibility.setQueryDocument(outer.toString());

        return  queryWithVisibility;
    }
    public List<PercolateQuery> visibilityFilterPercolateQueries(List<PercolateQuery> percolateQueryList,
            EzSecurityToken securityToken) throws TException {
        List<PercolateQuery> percolateQueriesToReturn = new LinkedList<>();

        Map<String, List<PercolateQuery>> percolatorIdToDocumentHit = new HashMap<>();
        Set<String> percolatorIds = new HashSet<>();
        for(PercolateQuery percolateQuery: percolateQueryList){
            List<PercolateQuery>docHits = percolatorIdToDocumentHit.get(percolateQuery.getId());
            if(docHits==null)
                docHits = new LinkedList<>();
            docHits.add(percolateQuery);
            percolatorIdToDocumentHit.put(percolateQuery.getId(),docHits);
            percolatorIds.add(percolateQuery.getId());
        }

        List<Document> documents = documentStore.get(percolatorIds,PERCOLATOR_TYPE,securityToken);

        for(Document document: documents){
            List<PercolateQuery> documentIdHit = percolatorIdToDocumentHit.get(document.get_id());
            percolateQueriesToReturn.addAll(documentIdHit);
        }
        return percolateQueriesToReturn;
    }
}
