package com.omnissa.access.approval.repository;

import com.omnissa.access.approval.model.security.DirectoryProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DirectoryProfileRepository extends JpaRepository<DirectoryProfile, Long> {

    DirectoryProfile findByIdentity(String identity);
}
