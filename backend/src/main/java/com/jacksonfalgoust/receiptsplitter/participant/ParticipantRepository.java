package com.jacksonfalgoust.receiptsplitter.participant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ParticipantRepository extends JpaRepository<Participant, Long> {

    /** Re-identifies a returning browser on reconnect, in place of a login. */
    Optional<Participant> findByBillIdAndSessionToken(Long billId, String sessionToken);
}
