package com.jacksonfalgoust.receiptsplitter.bill;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BillRepository extends JpaRepository<Bill, Long> {

    /**
     * Room codes are stored uppercase; callers normalize input with
     * {@code toUpperCase(Locale.ROOT)} so this lookup uses the unique index.
     */
    Optional<Bill> findByRoomCode(String roomCode);

    boolean existsByRoomCode(String roomCode);
}
