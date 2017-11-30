/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.plugin;

import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.sql.analysis.catalog.FilteredCatalog;

import java.util.Collections;

import static org.hamcrest.Matchers.empty;
import static org.mockito.Mockito.mock;

public class SqlPluginTests  extends ESTestCase {

    public void testSqlDisabled() {
        SqlPlugin plugin = new SqlPlugin(false, new SqlLicenseChecker(() -> {}, () -> {}));
        assertThat(plugin.createComponents(mock(Client.class), mock(FilteredCatalog.Filter.class)), empty());
        assertThat(plugin.getActions(), empty());
        assertThat(plugin.getRestHandlers(Settings.EMPTY, mock(RestController.class),
                new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS),
                IndexScopedSettings.DEFAULT_SCOPED_SETTINGS, new SettingsFilter(Settings.EMPTY, Collections.emptyList()),
                mock(IndexNameExpressionResolver.class), () -> mock(DiscoveryNodes.class)), empty());
    }

}
