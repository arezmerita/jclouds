/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jclouds.azurecompute.arm.compute.strategy;

import static com.google.common.base.Predicates.not;
import static com.google.common.base.Predicates.notNull;
import static com.google.common.collect.Iterables.filter;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Maps.filterValues;
import static org.jclouds.azurecompute.arm.config.AzureComputeProperties.TIMEOUT_RESOURCE_DELETED;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.jclouds.azurecompute.arm.AzureComputeApi;
import org.jclouds.azurecompute.arm.compute.domain.ResourceGroupAndName;
import org.jclouds.azurecompute.arm.domain.AvailabilitySet;
import org.jclouds.azurecompute.arm.domain.DataDisk;
import org.jclouds.azurecompute.arm.domain.IdReference;
import org.jclouds.azurecompute.arm.domain.IpConfiguration;
import org.jclouds.azurecompute.arm.domain.ManagedDiskParameters;
import org.jclouds.azurecompute.arm.domain.NetworkInterfaceCard;
import org.jclouds.azurecompute.arm.domain.NetworkSecurityGroup;
import org.jclouds.azurecompute.arm.domain.OSDisk;
import org.jclouds.azurecompute.arm.domain.VirtualMachine;
import org.jclouds.azurecompute.arm.features.NetworkSecurityGroupApi;
import org.jclouds.compute.functions.GroupNamingConvention;
import org.jclouds.compute.reference.ComputeServiceConstants;
import org.jclouds.javax.annotation.Nullable;
import org.jclouds.logging.Logger;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;

@Singleton
public class CleanupResources {

   @Resource
   @Named(ComputeServiceConstants.COMPUTE_LOGGER)
   protected Logger logger = Logger.NULL;

   private final AzureComputeApi api;
   private final Predicate<URI> resourceDeleted;
   private final GroupNamingConvention.Factory namingConvention;

   @Inject
   CleanupResources(AzureComputeApi azureComputeApi, @Named(TIMEOUT_RESOURCE_DELETED) Predicate<URI> resourceDeleted,
         GroupNamingConvention.Factory namingConvention) {
      this.api = azureComputeApi;
      this.resourceDeleted = resourceDeleted;
      this.namingConvention = namingConvention;
   }

   public boolean cleanupNode(final String id) {
      ResourceGroupAndName resourceGroupAndName = ResourceGroupAndName.fromSlashEncoded(id);
      String resourceGroupName = resourceGroupAndName.resourceGroup();

      VirtualMachine virtualMachine = api.getVirtualMachineApi(resourceGroupName).get(resourceGroupAndName.name());
      if (virtualMachine == null) {
         return true;
      }

      logger.debug(">> destroying %s ...", id);
      boolean vmDeleted = deleteVirtualMachine(resourceGroupName, virtualMachine);

      // We don't delete the network here, as it is global to the resource
      // group. It will be deleted when the resource group is deleted

      cleanupVirtualMachineNICs(virtualMachine);
      cleanupManagedDisks(virtualMachine);
      cleanupAvailabilitySetIfOrphaned(virtualMachine);

      return vmDeleted;
   }

   public boolean cleanupVirtualMachineNICs(VirtualMachine virtualMachine) {
      boolean deleted = true;
      for (IdReference nicRef : virtualMachine.properties().networkProfile().networkInterfaces()) {
         String nicResourceGroup = nicRef.resourceGroup();
         String nicName = nicRef.name();
         NetworkInterfaceCard nic = api.getNetworkInterfaceCardApi(nicRef.resourceGroup()).get(nicName);
         
         Iterable<IdReference> publicIps = getPublicIps(nic);

         logger.debug(">> destroying nic %s...", nicName);
         URI nicDeletionURI = api.getNetworkInterfaceCardApi(nicResourceGroup).delete(nicName);
         deleted &= nicDeletionURI == null || resourceDeleted.apply(nicDeletionURI);

         for (IdReference publicIp : publicIps) {
            String publicIpResourceGroup = publicIp.resourceGroup();
            String publicIpName = publicIp.name();
            
            logger.debug(">> deleting public ip nic %s...", publicIpName);
            deleted &= api.getPublicIPAddressApi(publicIpResourceGroup).delete(publicIpName);
         }
      }
      return deleted;
   }

   public boolean cleanupManagedDisks(VirtualMachine virtualMachine) {
      Map<String, URI> deleteJobs = new HashMap<String, URI>();

      OSDisk osDisk = virtualMachine.properties().storageProfile().osDisk();
      deleteManagedDisk(osDisk.managedDiskParameters(), deleteJobs);

      for (DataDisk dataDisk : virtualMachine.properties().storageProfile().dataDisks()) {
         deleteManagedDisk(dataDisk.managedDiskParameters(), deleteJobs);
      }
      
      Set<String> nonDeletedDisks = filterValues(deleteJobs, not(resourceDeleted)).keySet();
      if (!nonDeletedDisks.isEmpty()) {
         logger.warn(">> could not delete disks: %s", Joiner.on(',').join(nonDeletedDisks));
      }
      
      return nonDeletedDisks.isEmpty();
   }
   
   private void deleteManagedDisk(@Nullable ManagedDiskParameters managedDisk, Map<String, URI> deleteJobs) {
      if (managedDisk != null) {
         IdReference diskRef = IdReference.create(managedDisk.id());
         logger.debug(">> deleting managed disk %s...", diskRef.name());
         URI uri = api.getDiskApi(diskRef.resourceGroup()).delete(diskRef.name());
         if (uri != null) {
            deleteJobs.put(diskRef.name(), uri);
         }
      }
   }

   public boolean cleanupSecurityGroupIfOrphaned(String resourceGroup, String group) {
      String name = namingConvention.create().sharedNameForGroup(group);
      NetworkSecurityGroupApi sgapi = api.getNetworkSecurityGroupApi(resourceGroup);

      boolean deleted = false;

      try {
         NetworkSecurityGroup securityGroup = sgapi.get(name);
         if (securityGroup != null) {
            List<NetworkInterfaceCard> nics = securityGroup.properties().networkInterfaces();
            if (nics == null || nics.isEmpty()) {
               logger.debug(">> deleting orphaned security group %s from %s...", name, resourceGroup);
               try {
                  deleted = resourceDeleted.apply(sgapi.delete(name));
               } catch (Exception ex) {
                  logger.warn(ex, ">> error deleting orphaned security group %s from %s...", name, resourceGroup);
               }
            }
         }
      } catch (Exception ex) {
         logger.warn(ex, "Error deleting security groups for %s and group %s", resourceGroup, group);
      }

      return deleted;
   }

   public boolean cleanupAvailabilitySetIfOrphaned(VirtualMachine virtualMachine) {
      boolean deleted = true;
      IdReference availabilitySetRef = virtualMachine.properties().availabilitySet();

      if (availabilitySetRef != null) {
         String name = availabilitySetRef.name();
         String resourceGroup = availabilitySetRef.resourceGroup();
         AvailabilitySet availabilitySet = api.getAvailabilitySetApi(resourceGroup).get(name);

         if (isOrphanedJcloudsAvailabilitySet(availabilitySet)) {
            logger.debug(">> deleting orphaned availability set %s from %s...", name, resourceGroup);
            URI uri = api.getAvailabilitySetApi(resourceGroup).delete(name);
            deleted = uri == null || resourceDeleted.apply(uri);
         }
      }

      return deleted;
   }

   public boolean deleteResourceGroupIfEmpty(String group) {
      boolean deleted = false;
      if (api.getResourceGroupApi().resources(group).isEmpty()) {
         logger.debug(">> the resource group %s is empty. Deleting...", group);
         deleted = resourceDeleted.apply(api.getResourceGroupApi().delete(group));
      }
      return deleted;
   }

   private Iterable<IdReference> getPublicIps(NetworkInterfaceCard nic) {
      return filter(transform(nic.properties().ipConfigurations(), new Function<IpConfiguration, IdReference>() {
         @Override
         public IdReference apply(IpConfiguration input) {
            return input.properties().publicIPAddress();
         }
      }), notNull());
   }

   private static boolean isOrphanedJcloudsAvailabilitySet(AvailabilitySet availabilitySet) {
      // We check for the presence of the 'jclouds' tag to make sure we only
      // delete availability sets that were automatically created by jclouds
      return availabilitySet != null
            && availabilitySet.tags() != null
            && availabilitySet.tags().containsKey("jclouds")
            && (availabilitySet.properties().virtualMachines() == null || availabilitySet.properties()
                  .virtualMachines().isEmpty());
   }

   private boolean deleteVirtualMachine(String group, VirtualMachine virtualMachine) {
      return resourceDeleted.apply(api.getVirtualMachineApi(group).delete(virtualMachine.name()));
   }

}
