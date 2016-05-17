// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.flows.domain;

import static com.google.common.collect.Sets.symmetricDifference;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.util.DateTimeUtils.earliestOf;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

import google.registry.dns.DnsQueue;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.DomainResource.Builder;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.SecDnsUpdateExtension;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;

import org.joda.time.DateTime;

import java.util.Set;

/**
 * An EPP flow that updates a domain resource.
 *
 * @error {@link google.registry.flows.EppException.UnimplementedExtensionException}
 * @error {@link google.registry.flows.ResourceCreateOrMutateFlow.OnlyToolCanPassMetadataException}
 * @error {@link google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException}
 * @error {@link google.registry.flows.ResourceMutateFlow.ResourceToMutateDoesNotExistException}
 * @error {@link google.registry.flows.ResourceUpdateFlow.AddRemoveSameValueEppException}
 * @error {@link google.registry.flows.ResourceUpdateFlow.ResourceHasClientUpdateProhibitedException}
 * @error {@link google.registry.flows.ResourceUpdateFlow.StatusNotClientSettableException}
 * @error {@link google.registry.flows.SingleResourceFlow.ResourceStatusProhibitsOperationException}
 * @error {@link BaseDomainUpdateFlow.EmptySecDnsUpdateException}
 * @error {@link BaseDomainUpdateFlow.MaxSigLifeChangeNotSupportedException}
 * @error {@link BaseDomainUpdateFlow.SecDnsAllUsageException}
 * @error {@link BaseDomainUpdateFlow.UrgentAttributeNotSupportedException}
 * @error {@link DomainFlowUtils.DuplicateContactForRoleException}
 * @error {@link DomainFlowUtils.LinkedResourcesDoNotExistException}
 * @error {@link DomainFlowUtils.LinkedResourceInPendingDeleteProhibitsOperationException}
 * @error {@link DomainFlowUtils.MissingAdminContactException}
 * @error {@link DomainFlowUtils.MissingContactTypeException}
 * @error {@link DomainFlowUtils.MissingTechnicalContactException}
 * @error {@link DomainFlowUtils.NameserversNotAllowedException}
 * @error {@link DomainFlowUtils.RegistrantNotAllowedException}
 * @error {@link DomainFlowUtils.TooManyDsRecordsException}
 * @error {@link DomainFlowUtils.TooManyNameserversException}
 */
public class DomainUpdateFlow extends BaseDomainUpdateFlow<DomainResource, Builder> {

  @Override
  protected void initDomainUpdateFlow() {
    registerExtensions(SecDnsUpdateExtension.class, MetadataExtension.class);
  }

  @Override
  protected Builder setDomainUpdateProperties(Builder builder) {
    // Check if the domain is currently in the sunrush add grace period.
    Optional<GracePeriod> sunrushAddGracePeriod = Iterables.tryFind(
        existingResource.getGracePeriods(),
        new Predicate<GracePeriod>() {
          @Override
          public boolean apply(GracePeriod gracePeriod) {
            return gracePeriod.isSunrushAddGracePeriod();
          }});

    // If this domain is currently in the sunrush add grace period, and we're updating it in a way
    // that will cause it to now get delegated (either by setting nameservers, or by removing a
    // clientHold or serverHold), then that will remove the sunrush add grace period and convert
    // that to a standard add grace period.
    DomainResource updatedDomain = builder.build();
    builder = updatedDomain.asBuilder();
    if (sunrushAddGracePeriod.isPresent() && updatedDomain.shouldPublishToDns()) {
      // Remove the sunrush grace period and write a billing event cancellation for it.
      builder.removeGracePeriod(sunrushAddGracePeriod.get());
      BillingEvent.Cancellation billingEventCancellation = BillingEvent.Cancellation
          .forGracePeriod(sunrushAddGracePeriod.get(), historyEntry, targetId);

      // Compute the expiration time of the add grace period. We will not allow it to be after the
      // sunrush add grace period expiration time (i.e. you can't get extra add grace period by
      // setting a nameserver).
      DateTime addGracePeriodExpirationTime = earliestOf(
          now.plus(Registry.get(existingResource.getTld()).getAddGracePeriodLength()),
          sunrushAddGracePeriod.get().getExpirationTime());

      // Create a new billing event for the add grace period. Note that we do this even if it would
      // occur at the same time as the sunrush add grace period, as the event time will differ
      // between them.
      BillingEvent.OneTime originalAddEvent =
          sunrushAddGracePeriod.get().getOneTimeBillingEvent().get();
      BillingEvent.OneTime billingEvent = new BillingEvent.OneTime.Builder()
          .setReason(Reason.CREATE)
          .setTargetId(targetId)
          .setFlags(originalAddEvent.getFlags())
          .setClientId(sunrushAddGracePeriod.get().getClientId())
          .setCost(originalAddEvent.getCost())
          .setPeriodYears(originalAddEvent.getPeriodYears())
          .setEventTime(now)
          .setBillingTime(addGracePeriodExpirationTime)
          .setParent(historyEntry)
          .build();

      // Set the add grace period on the domain.
      builder.addGracePeriod(GracePeriod.forBillingEvent(GracePeriodStatus.ADD, billingEvent));

      // Save the billing events.
      ofy().save().entities(billingEvent, billingEventCancellation);
    }

    return builder;
  }

  @Override
  protected final void modifyRelatedResources() {
    // Determine the status changes, and filter to server statuses.
    // If any of these statuses have been added or removed, bill once.
    if (metadataExtension != null && metadataExtension.getRequestedByRegistrar()) {
      Set<StatusValue> statusDifferences =
          symmetricDifference(existingResource.getStatusValues(), newResource.getStatusValues());
      if (Iterables.any(statusDifferences, new Predicate<StatusValue>() {
          @Override
          public boolean apply(StatusValue statusValue) {
            return statusValue.isChargedStatus();
          }})) {
        BillingEvent.OneTime billingEvent = new BillingEvent.OneTime.Builder()
          .setReason(Reason.SERVER_STATUS)
          .setTargetId(targetId)
          .setClientId(getClientId())
          .setCost(Registry.get(existingResource.getTld()).getServerStatusChangeCost())
          .setEventTime(now)
          .setBillingTime(now)
          .setParent(historyEntry)
          .build();
        ofy().save().entity(billingEvent);
      }
    }
  }

  @Override
  protected void enqueueTasks() {
    DnsQueue.create().addDomainRefreshTask(existingResource.getFullyQualifiedDomainName());
  }

  @Override
  protected final HistoryEntry.Type getHistoryEntryType() {
    return HistoryEntry.Type.DOMAIN_UPDATE;
  }
}