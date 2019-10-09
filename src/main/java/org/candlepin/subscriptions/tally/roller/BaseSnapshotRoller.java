/*
 * Copyright (c) 2009 - 2019 Red Hat, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Red Hat trademarks are not licensed under GPLv3. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.subscriptions.tally.roller;

import org.candlepin.subscriptions.db.TallySnapshotRepository;
import org.candlepin.subscriptions.db.model.Granularity;
import org.candlepin.subscriptions.db.model.TallySnapshot;
import org.candlepin.subscriptions.tally.AccountUsageCalculation;
import org.candlepin.subscriptions.tally.ProductUsageCalculation;
import org.candlepin.subscriptions.util.ApplicationClock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Base class for all usage snapshot rollers. A snapshot roller is responsible compressing
 * finer granularity snapshots into more compressed snapshots. For example, rolling daily
 * snapshots into weekly snapshots or rolling weekly snapshots into monthly snapshots.
 */
public abstract class BaseSnapshotRoller {

    private static final Logger log = LoggerFactory.getLogger(BaseSnapshotRoller.class);

    protected  TallySnapshotRepository tallyRepo;
    protected ApplicationClock clock;

    public BaseSnapshotRoller(TallySnapshotRepository tallyRepo, ApplicationClock clock) {
        this.tallyRepo = tallyRepo;
        this.clock = clock;
    }

    /**
     * Roll the snapshots for the given account.
     *
     * @param accounts the accounts of the snapshots to roll.
     * @param accountCalcs the current calculations from the host inventory.
     */
    public abstract void rollSnapshots(Collection<String> accounts,
        Collection<AccountUsageCalculation> accountCalcs);

    protected TallySnapshot createSnapshotFromProductUsageCalculation(String account, String owner,
        ProductUsageCalculation productCalc, Granularity granularity) {
        TallySnapshot snapshot = new TallySnapshot();
        snapshot.setProductId(productCalc.getProductId());
        snapshot.setGranularity(granularity);
        snapshot.setOwnerId(owner);
        snapshot.setAccountNumber(account);
        snapshot.setSnapshotDate(getSnapshotDate(granularity));
        snapshot.setCores(productCalc.getTotalCores());
        snapshot.setSockets(productCalc.getTotalSockets());
        snapshot.setInstanceCount(productCalc.getTotalInstanceCount());
        snapshot.setPhysicalCores(productCalc.getTotalPhysicalCores());
        snapshot.setPhysicalSockets(productCalc.getTotalPhysicalSockets());
        snapshot.setPhysicalInstanceCount(productCalc.getTotalPhysicalInstanceCount());
        return snapshot;
    }

    protected OffsetDateTime getSnapshotDate(Granularity granularity) {
        switch (granularity) {
            case QUARTERLY:
                return clock.startOfCurrentQuarter();
            case WEEKLY:
                return clock.startOfCurrentWeek();
            case MONTHLY:
                return clock.startOfCurrentMonth();
            case YEARLY:
                return clock.startOfCurrentYear();
            default:
                return clock.now();
        }
    }

    @SuppressWarnings("indentation")
    protected Map<String, List<TallySnapshot>> getCurrentSnapshotsByAccount(Collection<String> accounts,
        Collection<String> products, Granularity granularity, OffsetDateTime begin, OffsetDateTime end) {
        try (Stream<TallySnapshot> snapStream =
            tallyRepo.findByAccountNumberInAndProductIdInAndGranularityAndSnapshotDateBetween(
                accounts, products, granularity, begin, end)) {
            return snapStream.collect(Collectors.groupingBy(TallySnapshot::getAccountNumber));
        }
    }

    protected void updateSnapshots(Collection<AccountUsageCalculation> accountCalcs,
        Map<String, List<TallySnapshot>> existingSnaps, Granularity targetGranularity) {
        List<TallySnapshot> snaps = new LinkedList<>();
        for (AccountUsageCalculation accountCalc : accountCalcs) {
            String account = accountCalc.getAccount();

            Map<String, TallySnapshot> accountSnapsByProduct = new HashMap<>();
            if (existingSnaps.containsKey(account)) {
                accountSnapsByProduct = existingSnaps.get(account)
                    .stream()
                    .collect(Collectors.toMap(TallySnapshot::getProductId, Function.identity()));
            }

            for (String product : accountCalc.getProducts()) {
                TallySnapshot snap = accountSnapsByProduct.get(product);
                ProductUsageCalculation productCalc = accountCalc.getProductCalculation(product);
                if (snap == null) {
                    snap = createSnapshotFromProductUsageCalculation(accountCalc.getAccount(),
                        accountCalc.getOwner(), productCalc, targetGranularity);
                    snaps.add(snap);
                }
                else if (updateMaxValues(snap, productCalc)) {
                    snaps.add(snap);
                }
            }
        }
        log.debug("Persisting {} {} snapshots.", snaps.size(), targetGranularity);
        tallyRepo.saveAll(snaps);
    }

    protected Set<String> getApplicableProducts(Collection<AccountUsageCalculation> accountCalcs) {
        Set<String> prods = new HashSet<>();
        accountCalcs.forEach(calc -> prods.addAll(calc.getProducts()));
        return prods;
    }

    private boolean updateMaxValues(TallySnapshot toUpdate, ProductUsageCalculation productCalc) {
        boolean changed = false;
        boolean overrideMaxCheck = Granularity.DAILY.equals(toUpdate.getGranularity());
        if (overrideMaxCheck || productCalc.getTotalCores() > toUpdate.getCores()) {
            toUpdate.setCores(productCalc.getTotalCores());
            changed = true;
        }

        if (overrideMaxCheck || productCalc.getTotalSockets() > toUpdate.getSockets()) {
            toUpdate.setSockets(productCalc.getTotalSockets());
            changed = true;
        }

        if (overrideMaxCheck || productCalc.getTotalInstanceCount() > toUpdate.getInstanceCount()) {
            toUpdate.setInstanceCount(productCalc.getTotalInstanceCount());
            changed = true;
        }

        if (overrideMaxCheck || productCalc.getTotalPhysicalCores() > toUpdate.getPhysicalCores()) {
            toUpdate.setPhysicalCores(productCalc.getTotalPhysicalCores());
            changed = true;
        }

        if (overrideMaxCheck || productCalc.getTotalPhysicalSockets() > toUpdate.getPhysicalSockets()) {
            toUpdate.setPhysicalSockets(productCalc.getTotalPhysicalSockets());
            changed = true;
        }

        if (overrideMaxCheck ||
            productCalc.getTotalPhysicalInstanceCount() > toUpdate.getPhysicalInstanceCount()) {
            toUpdate.setPhysicalInstanceCount(productCalc.getTotalPhysicalInstanceCount());
            changed = true;
        }

        return changed;
    }

}
