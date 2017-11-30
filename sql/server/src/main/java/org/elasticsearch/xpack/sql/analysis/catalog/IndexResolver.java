/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.analysis.catalog;

import com.carrotsearch.hppc.cursors.ObjectObjectCursor;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.get.GetIndexRequest;
import org.elasticsearch.action.admin.indices.get.GetIndexRequest.Feature;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.xpack.sql.analysis.catalog.Catalog.GetIndexResult;
import org.elasticsearch.xpack.sql.type.DataType;
import org.elasticsearch.xpack.sql.type.Types;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IndexResolver {

    private final Client client;
    private final FilteredCatalog.Filter catalogFilter;

    public IndexResolver(Client client, FilteredCatalog.Filter catalogFilter) {
        this.client = client;
        this.catalogFilter = catalogFilter;
    }

    /**
     * Resolves a single catalog by name. 
     */
    public void asCatalog(final String index, ActionListener<Catalog> listener) {
        GetIndexRequest getIndexRequest = createGetIndexRequest(index);
        client.admin().indices().getIndex(getIndexRequest, ActionListener.wrap(getIndexResponse -> {
            Map<String, GetIndexResult> results = new HashMap<>();
            if (getIndexResponse.getMappings().size() > 1) {
                results.put(index, GetIndexResult.invalid(
                        "[" + index + "] is an alias pointing to more than one index which is currently incompatible with sql"));
            } else if (getIndexResponse.getMappings().size() == 1){
                ObjectObjectCursor<String, ImmutableOpenMap<String, MappingMetaData>> indexMappings =
                        getIndexResponse.getMappings().iterator().next();
                String concreteIndex = indexMappings.key;
                /*
                 * here we don't support wildcards: we can either have an alias or an index. However names get resolved (through
                 * security or not) we need to preserve the original names as they will be used in the subsequent search request.
                 * With security enabled, if the user is authorized for an alias and not its corresponding concrete index, we have to
                 * make sure that the search is executed against the same alias name from the original command, rather than
                 * the resolved concrete index that we get back from the get index API
                 */
                results.put(index, buildGetIndexResult(concreteIndex, index, indexMappings.value));
            }
            Catalog catalog = new PreloadedCatalog(results);
            catalog = catalogFilter != null ? new FilteredCatalog(catalog, catalogFilter) : catalog;
            listener.onResponse(catalog);
        }, listener::onFailure));
    }

    /**
     *  Discover (multiple) matching indices for a given name.
     */
    //TODO this method can take a single index pattern once SqlGetIndicesAction is removed
    public void asList(ActionListener<List<EsIndex>> listener, String... indices) {
        GetIndexRequest getIndexRequest = createGetIndexRequest(indices);
        client.admin().indices().getIndex(getIndexRequest, ActionListener.wrap(getIndexResponse -> {
            Map<String, GetIndexResult> map = new HashMap<>();
            for (ObjectObjectCursor<String, ImmutableOpenMap<String, MappingMetaData>> indexMappings : getIndexResponse.getMappings()) {
                /*
                 * We support wildcard expressions here, and it's only for commands that only perform the get index call.
                 * We can and simply have to use the concrete index name and show that to users.
                 * Get index against an alias with security enabled, where the user has only access to get mappings for the alias
                 * and not the concrete index: there is a well known information leak of the concrete index name in the response.
                 */
                String concreteIndex = indexMappings.key;
                map.put(concreteIndex, buildGetIndexResult(concreteIndex, concreteIndex, indexMappings.value));
            }
            List<EsIndex> results = new ArrayList<>(map.size());
            for (GetIndexResult result : map.values()) {
                if (result.isValid()) {
                    //as odd as this is, it will go away once mappings are returned filtered
                    GetIndexResult filtered = catalogFilter != null ? catalogFilter.filterIndex(result) : result;
                    results.add(filtered.get());
                }
            }
            results.sort(Comparator.comparing(EsIndex::name));
            listener.onResponse(results);
        }, listener::onFailure));
    }

    private static GetIndexRequest createGetIndexRequest(String... indices) {
        return new GetIndexRequest()
                .local(true)
                .indices(indices)
                .features(Feature.MAPPINGS)
                //lenient because we throw our own errors looking at the response e.g. if something was not resolved
                //also because this way security doesn't throw authorization exceptions but rather honours ignore_unavailable
                .indicesOptions(IndicesOptions.lenientExpandOpen());
    }

    private static GetIndexResult buildGetIndexResult(String concreteIndex, String indexOrAlias,
                                                      ImmutableOpenMap<String, MappingMetaData> mappings) {
        if (concreteIndex.startsWith(".")) {
            //Indices that start with "." are considered internal and should not be available to SQL
            return GetIndexResult.notFound(indexOrAlias);
        }

        // Make sure that the index contains only a single type
        MappingMetaData singleType = null;
        List<String> typeNames = null;
        for (ObjectObjectCursor<String, MappingMetaData> type : mappings) {
            //Default mappings are ignored as they are applied to each type. Each type alone holds all of its fields.
            if ("_default_".equals(type.key)) {
                continue;
            }
            if (singleType != null) {
                // There are more than one types
                if (typeNames == null) {
                    typeNames = new ArrayList<>();
                    typeNames.add(singleType.type());
                }
                typeNames.add(type.key);
            }
            singleType = type.value;
        }

        if (singleType == null) {
            return GetIndexResult.invalid("[" + indexOrAlias + "] doesn't have any types so it is incompatible with sql");
        } else if (typeNames != null) {
            Collections.sort(typeNames);
            return GetIndexResult.invalid(
                    "[" + indexOrAlias + "] contains more than one type " + typeNames + " so it is incompatible with sql");
        } else {
            Map<String, DataType> mapping = Types.fromEs(singleType.sourceAsMap());
            return GetIndexResult.valid(new EsIndex(indexOrAlias, mapping));
        }
    }
}