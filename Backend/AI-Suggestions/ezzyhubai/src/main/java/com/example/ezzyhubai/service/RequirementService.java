package com.example.ezzyhubai.service;

import com.example.ezzyhubai.entity.Requirement;
import com.example.ezzyhubai.repository.RequirementRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class RequirementService {

    private final RequirementRepository repository;

    public RequirementService(RequirementRepository repository) {
        this.repository = repository;
    }

    public Requirement create(Requirement requirement) {
        return repository.save(requirement);
    }

    public List<Requirement> getAll() {
        return repository.findAll();
    }

    public Requirement getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("Requirement not found"));
    }
    public Optional<Requirement> findByName(
            String name) {

        return repository.findByName(
                name);
    }
    public void delete(Long id) {
        repository.deleteById(id);
    }
}