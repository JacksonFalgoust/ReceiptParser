package com.jacksonfalgoust.receiptsplitter.claim;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ItemClaimRepository extends JpaRepository<ItemClaim, Long> {

    /** Every claim on a bill, for room state and the settle-up calculation. */
    List<ItemClaim> findByItemBillId(Long billId);

    /** One participant's claim on one unit, for unclaiming. */
    Optional<ItemClaim> findByItemIdAndParticipantIdAndUnitIndex(
            Long itemId, Long participantId, int unitIndex);
}
