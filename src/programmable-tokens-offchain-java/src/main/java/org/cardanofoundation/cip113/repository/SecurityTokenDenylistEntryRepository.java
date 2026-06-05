package org.cardanofoundation.cip113.repository;

import org.cardanofoundation.cip113.entity.SecurityTokenDenylistEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SecurityTokenDenylistEntryRepository
        extends JpaRepository<SecurityTokenDenylistEntryEntity, Long> {

    List<SecurityTokenDenylistEntryEntity> findByProgrammableTokenPolicyId(String policyId);

    Optional<SecurityTokenDenylistEntryEntity> findByProgrammableTokenPolicyIdAndMemberPkh(
            String policyId, String memberPkh);

    boolean existsByProgrammableTokenPolicyIdAndMemberPkh(String policyId, String memberPkh);

    void deleteByProgrammableTokenPolicyIdAndMemberPkh(String policyId, String memberPkh);
}
