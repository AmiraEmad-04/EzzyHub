package com.example.ezzyhubai.repository;

import com.example.ezzyhubai.entity.GenerationBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GenerationBatchRepository
        extends JpaRepository<
                GenerationBatch,
                Long> {
}