# Azure Local Plugin for Morpheus – Feature Summary

## Overview

The plugin adds **Azure Local** as a cloud type in Morpheus, so Arc virtual machines on an Azure Local instance can be discovered, provisioned and managed from Morpheus like any other cloud.

- **Built for:** Morpheus 6.3.0+ (plugin API 1.2.13).
- **Connection:** public **Azure Resource Manager** (`management.azure.com`) using a Microsoft Entra ID app registration (client ID + secret). No direct connection to the cluster is required — everything goes through ARM and the Arc control plane.
- **Changes in Azure:** only Arc machines, virtual machine instances and network interfaces that you create or manage through the plugin. The sync itself only reads.
- **Scope:** *connected* Azure Local deployments only. Disconnected operations are a separate support boundary and are out of scope.

## Cloud and inventory sync

On every cloud refresh the plugin brings the Azure Local inventory for the scoped resource group into Morpheus and keeps it current.

| Azure Local | Becomes in Morpheus |
| --- | --- |
| Arc VMs (`Microsoft.HybridCompute/machines` + `Microsoft.AzureStackHCI/virtualMachineInstances`) | Compute servers, with power state, cores, memory and IP address |
| Logical networks (`Microsoft.AzureStackHCI/logicalNetworks`) | Networks, with CIDR, VLAN ID, gateway and DNS servers |
| Storage paths (`Microsoft.AzureStackHCI/storageContainers`) | Datastores, with path and capacity |
| Gallery images and marketplace gallery images | Virtual images, with OS platform |

- Machines that are not Azure Local VMs (e.g. plain Arc-enabled servers) are skipped.
- Discovered VMs appear as unmanaged **Azure Local Instance** servers; records removed in Azure are removed from Morpheus on the next sync.

## Provisioning and plans

VMs are provisioned from a gallery image onto the Azure Local instance, targeted at the cloud's **custom location**.

- **Flow:** Arc machine shell → network interface on the chosen logical network → `virtualMachineInstances/default` with the custom location as extended location, polled until `provisioningState` is terminal.
- **Images:** any gallery or marketplace gallery image synced from the resource group.
- **Placement:** logical network and storage path can be chosen per-provision, or fall back to the defaults configured on the cloud.
- **Guest setup:** admin username/password are applied through `osProfile` (Linux and Windows configurations); the VM's computer name is derived from the instance name.
- **Firmware:** UEFI with Secure Boot enabled.
- **Plans:** 6 fixed sizes from 1 CPU / 2 GB to 16 CPU / 64 GB, plus a **Custom** plan with configurable cores and memory.
- **IP discovery:** after creation the plugin reads the NIC's private IP and hands it to Morpheus for connectivity and agent install.

## Day-2 operations

| Feature | What it does |
| --- | --- |
| Power | Start, stop and restart through ARM, with long-running-operation polling |
| Delete | Removes the virtual machine instance, the Arc machine and the plugin-created NIC from Azure |
| Inventory refresh | Specs, power state, name and IP changes in Azure are reflected on the Morpheus record |

### Not yet implemented (v1 limitations)

- Reconfigure (resize), snapshots, extra data disks and multiple NICs
- Cloud-init custom data (the `2024-01-01` `virtualMachineInstances` API does not expose it) — guest credentials are applied via `osProfile`
- Suspend/pause, console access, backups, costing
- Azure US Gov / China sovereign cloud endpoints (global Azure only)

---

# Azure Prerequisites

Everything below must exist **before** adding the cloud in Morpheus. Items 1–4 are normally created when the Azure Local instance is deployed; items 5–7 are day-1 admin tasks on the instance; items 8–9 are specific to this integration.

## 1. Azure subscription and Entra tenant

- An active Azure subscription (note the **Subscription ID**).
- A Microsoft Entra ID tenant (note the **Directory (tenant) ID**).

## 2. Azure Local instance (Arc-connected)

- An Azure Local instance (cluster) deployed and **registered with Azure Arc**, visible as a `Microsoft.AzureStackHCI/clusters` resource in a resource group.
- The instance must be **connected** — able to reach Azure endpoints and syncing regularly. Disconnected deployments are not supported by this plugin.

## 3. Arc resource bridge

- The **Arc resource bridge** (appliance) deployed on the instance and in `Running` state. This is created automatically by current Azure Local deployments; verify under **Azure portal → your Azure Local instance → Infrastructure**.

## 4. Custom location

- A **custom location** (`Microsoft.ExtendedLocation/customLocations`) created on the instance with the Azure Local VM cluster extension enabled, so ARM can target VM deployments at the cluster.
- Verify it exists in the same resource group and shows the `microsoft.azstackhci.operator` cluster extension.

## 5. Logical networks

- At least one **logical network** (`Microsoft.AzureStackHCI/logicalNetworks`) created on the instance, mapped to a Hyper-V virtual switch, with either **DHCP** or a **static** IP pool (address prefix, gateway, DNS).
- VMs provisioned by Morpheus attach their NIC to one of these; the plugin syncs them as Morpheus networks.

## 6. Storage paths

- At least one **storage path** (`Microsoft.AzureStackHCI/storageContainers`) on a Cluster Shared Volume for VM configuration files and disks.
- Set one as the cloud's **Default Storage Path** so provisions work without an explicit choice.

## 7. VM images

- At least one **gallery image** (`galleryImages`) or **marketplace gallery image** (`marketplaceGalleryImages`) downloaded to the instance — from Azure Marketplace, an existing Azure VM image, or a custom VHD/VHDX.
- Images must be in the scoped resource group to be synced and selectable.

## 8. App registration (service principal)

The plugin authenticates with an Entra ID app registration using a client secret.

### Create the app registration

1. Log into the Azure portal and open **App registrations**.
2. Click **+ New registration**, give it a name (e.g. `morpheus-azure-local`), and click **Register**.
3. From the **Overview** page copy:
   - **Directory (tenant) ID** → used as *Tenant ID* in Morpheus
   - **Application (client) ID** → used as *Client ID* in Morpheus

### Generate a client secret

1. In the app registration select **Certificates & secrets → + New client secret**.
2. Add a description, pick a duration, click **Add**.
3. **Copy the secret Value immediately** (it is not shown again) → used as *Client Secret* in Morpheus.

### Assign roles

On the **resource group** containing the Azure Local instance (**Access control (IAM) → + Add → Add role assignment**), assign to the app registration:

| Role | Why |
| --- | --- |
| **Azure Stack HCI VM Contributor** | Create/manage Arc VMs, NICs and virtual machine instances |
| **Reader** | Enumerate the cluster, custom location, networks, storage paths and images |

For lab use, a single **Contributor** assignment on the resource group also works. Subscription-level assignment is only needed if you want resource-group dropdowns across the whole subscription.

> Unlike the Morpheus Azure (public) integration, this plugin does **not** require Owner/Contributor on the subscription — resource-group-scoped least privilege is sufficient.

## 9. Network access from the Morpheus appliance

The Morpheus appliance needs outbound **HTTPS (443)** to:

| Endpoint | Purpose |
| --- | --- |
| `https://login.microsoftonline.com` | Entra ID token requests (client credentials) |
| `https://management.azure.com` | All ARM management and inventory calls |

For agent install and guest management, the appliance (or a configured proxy/worker) must also be able to reach the **VM IPs on the logical networks** over SSH (22) / WinRM (5985-5986), and the VMs must be able to reach the **Appliance URL** over 443 for agent reporting.

---

# Adding the Cloud in Morpheus

1. Upload the plugin jar under **Administration → Integrations → Plugins**.
2. Navigate to **Infrastructure → Clouds → + Add** and select **Azure Local**.
3. Populate the following:

| Field | Value |
| --- | --- |
| CREDENTIALS | A stored `client id and secret` credential, or enter Client ID / Client Secret locally |
| SUBSCRIPTION ID | The Azure Subscription ID from prerequisite 1 |
| TENANT ID | The Directory (tenant) ID from prerequisite 8 |
| CLIENT ID / CLIENT SECRET | From the app registration (prerequisite 8) |
| RESOURCE GROUP | The resource group containing the Azure Local instance; populates once credentials validate |
| AZURE LOCAL INSTANCE | Arc-enabled Azure Local instances (Azure Local clusters) discovered in the selected resource group |
| CUSTOM LOCATION | The custom location from prerequisite 4 |
| DEFAULT STORAGE PATH | Fallback placement for VM config/disks (prerequisite 6) |
| DEFAULT LOGICAL NETWORK | Fallback network for VM NICs (prerequisite 5) |
| INVENTORY EXISTING INSTANCES | Check to discover existing Arc VMs on the instance |

For full build and install details, see the [README](README.md).
