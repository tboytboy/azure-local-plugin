# Morpheus Azure Local Cloud Plugin

A [Morpheus](https://morpheusdata.com) cloud plugin for **Azure Local**. It connects Morpheus to a *connected* Azure Local deployment through public **Azure Resource Manager (ARM)**, using the `Microsoft.AzureStackHCI` resource provider and Arc-enabled machines — no agent or direct cluster access required.

> **Status: experimental / community plugin.** Built against `morpheus-plugin-api` 1.2.13. Not an official Morpheus or Microsoft product. Test in a lab before using anywhere that matters.

See **[FEATURES.md](FEATURES.md)** for the full feature summary, Azure prerequisites and cloud setup walkthrough.

## What it does

| Capability | Details |
|---|---|
| Cloud type | Adds an **Azure Local** cloud type with its own Create Cloud form (subscription, tenant, credentials, resource group, Azure Local instance, custom location, default storage path, default logical network) |
| Discovery (sync) | Arc VMs (`Microsoft.HybridCompute/machines` + `Microsoft.AzureStackHCI/virtualMachineInstances`), logical networks, storage paths (storage containers), gallery + marketplace gallery images |
| Provisioning | Creates Arc VMs on the Azure Local instance: Arc machine shell → network interface on a logical network → `virtualMachineInstances/default` targeted at the cloud's custom location |
| Power operations | Start / stop / restart / delete through ARM, with long-running operation polling |
| Plans | Seeded T-shirt sizes (1C/2G … 16C/64G) plus a custom plan |

### Scope and limitations (v1)

- **Connected deployments only.** Disconnected Azure Local operations are a different support boundary and are out of scope.
- Reconfigure (resize), snapshots, extra data disks, and multiple NICs are not implemented yet.
- Cloud-init custom data is not passed to the VM (the `2024-01-01` `virtualMachineInstances` API does not expose it); guest credentials are applied via `osProfile`.
- Costing, backups, and console access are not implemented.

## Requirements

- Morpheus appliance **6.3.0+**
- An Azure Local instance (cluster) that is **Arc-connected** with:
  - an Arc resource bridge and a **custom location**
  - at least one **logical network**
  - at least one **storage path** (storage container)
  - gallery or marketplace **images** downloaded to the cluster
- A Microsoft Entra ID **app registration** (service principal) with permissions on the resource group, e.g. `Azure Stack HCI VM Contributor` + `Reader` (or Contributor for lab use)

## Building

```bash
./gradlew shadowJar
```

The plugin jar is produced at `build/libs/azure-local-plugin-<version>-all.jar`. JDK 17+ is required to run the build (the plugin itself targets Java 11 bytecode).

## Installing

1. In Morpheus go to **Administration → Integrations → Plugins**.
2. Upload the shadow jar.
3. Create a cloud: **Infrastructure → Clouds → + Add → Azure Local**.

### Create Cloud fields

| Field | Notes |
|---|---|
| Credentials | `client-id-secret` credential store entry, or enter Client ID / Client Secret locally |
| Subscription ID / Tenant ID | From your Azure subscription / Entra tenant |
| Resource Group | Resource group containing the Azure Local instance |
| Azure Local Instance | Arc-enabled Azure Local instances (Azure Local clusters) discovered in the selected resource group |
| Custom Location | Arc custom location that deployments target |
| Default Storage Path | Used for VM config/disks when the provision request doesn't pick one |
| Default Logical Network | Used for NICs when the provision request doesn't pick one |

## How it talks to Azure

- OAuth2 client-credentials tokens from `login.microsoftonline.com` with the `https://management.azure.com/.default` scope
- All management calls to `management.azure.com` (ARM), including:
  - `Microsoft.AzureStackHCI/clusters`, `logicalNetworks`, `storageContainers`, `galleryImages`, `marketplaceGalleryImages`
  - `Microsoft.ExtendedLocation/customLocations`
  - `Microsoft.HybridCompute/machines` and the `virtualMachineInstances/default` child resource
- Async operations are polled via `Azure-AsyncOperation` / `provisioningState`

## Project layout

```
src/main/groovy/com/morpheusdata/azurelocal/
  AzureLocalPlugin.groovy                 # plugin entry point / provider registration
  AzureLocalCloudProvider.groovy          # cloud type, option types, refresh/sync orchestration
  AzureLocalProvisionProvider.groovy      # Arc VM provisioning lifecycle
  AzureLocalOptionSourceProvider.groovy   # dropdown data for cloud + provision forms
  AzureLocalApiService.groovy             # minimal ARM REST client (JDK HttpURLConnection)
  sync/                                   # VM / network / storage path / image syncs
src/assets/images/                        # cloud logos
docs/mockups/create-cloud.html            # the design mockup for the Create Cloud dialog
```

## Acknowledgements

Project structure inspired by the MIT-licensed [morpheus-proxmox-ve-plugin](https://github.com/NixndME/morpheus-proxmox-ve-plugin) and the [Morpheus plugin developer docs](https://developer.morpheusdata.com/).

## License

[MIT](LICENSE)
