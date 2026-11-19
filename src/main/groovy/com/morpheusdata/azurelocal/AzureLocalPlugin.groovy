package com.morpheusdata.azurelocal

import com.morpheusdata.core.Plugin
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import groovy.util.logging.Slf4j

/**
 * Entry point for the Azure Local cloud plugin.
 * Registers the cloud, provisioning and option-source providers.
 */
@Slf4j
class AzureLocalPlugin extends Plugin {

    @Override
    String getCode() {
        return 'azure-local'
    }

    @Override
    void initialize() {
        this.setName('Azure Local')
        this.registerProvider(new AzureLocalCloudProvider(this, this.morpheus))
        this.registerProvider(new AzureLocalProvisionProvider(this, this.morpheus))
        this.registerProvider(new AzureLocalOptionSourceProvider(this, this.morpheus))
    }

    /**
     * Builds the API auth/config map used by {@link AzureLocalApiService} from a saved cloud.
     * Credentials may come from a referenced AccountCredential (client-id-secret) or the
     * locally stored config fields.
     */
    Map getApiConfig(Cloud cloud) {
        def config = cloud.configMap ?: [:]

        if (!cloud.accountCredentialLoaded) {
            AccountCredential accountCredential = null
            try {
                accountCredential = this.morpheus.async.cloud.loadCredentials(cloud.id).blockingGet()
            } catch (e) {
                log.warn("Error loading cloud credentials for cloud ${cloud.id}: ${e.message}")
            }
            cloud.accountCredentialLoaded = true
            cloud.accountCredentialData = accountCredential?.data
        }

        String clientId = cloud.accountCredentialData?.containsKey('clientId') ?
                cloud.accountCredentialData.clientId : config.clientId
        String clientSecret = cloud.accountCredentialData?.containsKey('clientSecret') ?
                cloud.accountCredentialData.clientSecret : config.clientSecret

        return [
                subscriptionId         : config.subscriptionId,
                tenantId               : config.tenantId,
                clientId               : clientId,
                clientSecret           : clientSecret,
                cloudEnvironment       : config.cloudEnvironment ?: 'global',
                resourceGroup          : config.resourceGroup,
                clusterId              : config.cluster,
                customLocationId       : config.customLocation,
                defaultStoragePathId   : config.defaultStoragePath,
                defaultLogicalNetworkId: config.defaultLogicalNetwork
        ]
    }

    @Override
    void onDestroy() {
        // nothing to clean up
    }
}
