package com.globalpagegenerator.persistence.repository;

import com.globalpagegenerator.persistence.entity.Log;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LogRepository extends JpaRepository<Log, Long> { }
