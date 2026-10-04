package com.globalpagegenerator.persistence.repository;

import com.globalpagegenerator.persistence.entity.Service;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ServiceRepository extends JpaRepository<Service, Long> {
    Optional<Service> findByServiceName(String serviceName);
}
