package com.raph.goods.repository;

import java.util.List;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import com.raph.goods.entity.GoodsDocument;

public interface GoodsDocumentRepository extends ElasticsearchRepository<GoodsDocument, Long> {

    List<GoodsDocument> findByNameContainingOrDescriptionContaining(String nameKeyword, String descriptionKeyword);
}