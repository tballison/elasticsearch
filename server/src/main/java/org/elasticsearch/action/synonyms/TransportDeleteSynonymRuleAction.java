/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.action.synonyms;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.injection.guice.Inject;
import org.elasticsearch.synonyms.SynonymSequencer;
import org.elasticsearch.synonyms.SynonymsManagementAPIService;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

public class TransportDeleteSynonymRuleAction extends TransportMasterNodeAction<DeleteSynonymRuleAction.Request, SynonymUpdateResponse> {

    private final SynonymsManagementAPIService synonymsManagementAPIService;
    private final SynonymSequencer sequencer;

    @Inject
    public TransportDeleteSynonymRuleAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        Client client,
        SynonymSequencer sequencer
    ) {
        super(
            DeleteSynonymRuleAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            DeleteSynonymRuleAction.Request::new,
            SynonymUpdateResponse::new,
            EsExecutors.DIRECT_EXECUTOR_SERVICE
        );
        this.synonymsManagementAPIService = new SynonymsManagementAPIService(client);
        this.sequencer = sequencer;
    }

    @Override
    protected void masterOperation(
        Task task,
        DeleteSynonymRuleAction.Request request,
        ClusterState state,
        ActionListener<SynonymUpdateResponse> listener
    ) {
        sequencer.submit(
            () -> synonymsManagementAPIService.deleteSynonymRule(
                request.synonymsSetId(),
                request.synonymRuleId(),
                request.refresh(),
                sequencer.wrap(listener.map(SynonymUpdateResponse::new))
            )
        );
    }

    @Override
    protected ClusterBlockException checkBlock(DeleteSynonymRuleAction.Request request, ClusterState state) {
        return null;
    }
}
