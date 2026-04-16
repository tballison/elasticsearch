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
import org.elasticsearch.action.support.master.AcknowledgedResponse;
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

public class TransportDeleteSynonymsAction extends TransportMasterNodeAction<DeleteSynonymsAction.Request, AcknowledgedResponse> {

    private final SynonymsManagementAPIService synonymsManagementAPIService;
    private final SynonymSequencer sequencer;

    @Inject
    public TransportDeleteSynonymsAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        Client client,
        SynonymSequencer sequencer
    ) {
        super(
            DeleteSynonymsAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            DeleteSynonymsAction.Request::new,
            AcknowledgedResponse::readFrom,
            EsExecutors.DIRECT_EXECUTOR_SERVICE
        );
        this.synonymsManagementAPIService = new SynonymsManagementAPIService(client);
        this.sequencer = sequencer;
    }

    @Override
    protected void masterOperation(
        Task task,
        DeleteSynonymsAction.Request request,
        ClusterState state,
        ActionListener<AcknowledgedResponse> listener
    ) {
        sequencer.submit(
            () -> synonymsManagementAPIService.deleteSynonymsSet(request.synonymsSetId(), sequencer.wrap(listener))
        );
    }

    @Override
    protected ClusterBlockException checkBlock(DeleteSynonymsAction.Request request, ClusterState state) {
        return null;
    }
}
