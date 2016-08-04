package com.kaicube.snomed.elasticsnomed.services;

import com.kaicube.snomed.elasticsnomed.domain.*;
import com.kaicube.snomed.elasticsnomed.repositories.ConceptRepository;
import com.kaicube.snomed.elasticsnomed.repositories.DescriptionRepository;
import com.kaicube.snomed.elasticsnomed.repositories.ReferenceSetMemberRepository;
import com.kaicube.snomed.elasticsnomed.repositories.RelationshipRepository;
import org.elasticsearch.common.Strings;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.util.CloseableIterator;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.*;
import java.util.stream.Collectors;

import static org.elasticsearch.index.query.QueryBuilders.*;

@Service
public class ConceptService {

	@Autowired
	private ConceptRepository conceptRepository;

	@Autowired
	private DescriptionRepository descriptionRepository;

	@Autowired
	private RelationshipRepository relationshipRepository;

	@Autowired
	private ReferenceSetMemberRepository referenceSetMemberRepository;

	@Autowired
	private BranchService branchService;

	@Autowired
	private VersionControlHelper versionControlHelper;

	@Autowired
	private ElasticsearchTemplate elasticsearchTemplate;

	@Autowired
	private QueryIndexService queryIndexService;

	private Logger logger = LoggerFactory.getLogger(getClass());

	private static final PageRequest PAGE_REQUEST_LARGE = new PageRequest(0, 10000);

	public Concept find(String id, String path) {
		final Page<Concept> concepts = doFind(id, path, new PageRequest(0, 10));
		if (concepts.getTotalElements() > 1) {
			logger.error("Found more than one concept {}", concepts.getContent());
			throw new IllegalStateException("More than one concept found for id " + id + " on branch " + path);
		}
		Concept concept = concepts.getTotalElements() == 0 ? null : concepts.iterator().next();
		logger.info("Find id:{}, path:{} found:{}", id, path, concept);
		return concept;
	}

	public Page<Concept> findAll(String path, PageRequest pageRequest) {
		return doFind(null, path, pageRequest);
	}

	public Collection<ConceptMini> findConceptChildrenInferred(String conceptId, String path) {
		final QueryBuilder branchCriteria = versionControlHelper.getBranchCriteria(path);

		// Gather inferred children ids
		final Set<String> childrenIds = new HashSet<>();
		try (final CloseableIterator<Relationship> relationshipStream = openRelationshipStream(branchCriteria, termQuery("destinationId", conceptId))) {
			relationshipStream.forEachRemaining(relationship -> childrenIds.add(relationship.getSourceId()));
		}

		// Fetch concept details
		final Map<String, ConceptMini> conceptMiniMap = new HashMap<>();
		try (final CloseableIterator<Concept> conceptStream = elasticsearchTemplate.stream(new NativeSearchQueryBuilder()
				.withQuery(boolQuery()
						.must(branchCriteria)
						.must(termsQuery("conceptId", childrenIds))
				)
				.build(), Concept.class
		)) {
			conceptStream.forEachRemaining(concept -> conceptMiniMap.put(concept.getConceptId(), new ConceptMini(concept).setLeafInferred(true)));
		}

		// Find inferred children of the inferred children to set the isLeafInferred flag
		try (final CloseableIterator<Relationship> relationshipStream = openRelationshipStream(branchCriteria, termsQuery("destinationId", childrenIds))) {
			relationshipStream.forEachRemaining(relationship -> conceptMiniMap.get(relationship.getDestinationId()).setLeafInferred(false));
		}

		// Fetch descriptions and Lang refsets
		fetchDescriptions(branchCriteria, null, conceptMiniMap);

		return conceptMiniMap.values();
	}

	private CloseableIterator<Relationship> openRelationshipStream(QueryBuilder branchCriteria, QueryBuilder destinationCriteria) {
		return elasticsearchTemplate.stream(new NativeSearchQueryBuilder()
				.withQuery(boolQuery()
						.must(branchCriteria)
						.must(termQuery("active", true))
						.must(termQuery("typeId", Concepts.ISA))
						.must(destinationCriteria)
						.must(termQuery("characteristicTypeId", Concepts.INFERRED))
				)
				.build(), Relationship.class
		);
	}

	private Page<Concept> doFind(String id, String path, PageRequest pageRequest) {
		final QueryBuilder branchCriteria = versionControlHelper.getBranchCriteria(path);

		final BoolQueryBuilder builder = boolQuery()
				.must(branchCriteria);
		if (id != null) {
			builder.must(queryStringQuery(id).field("conceptId"));
		}

		final NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder()
				.withQuery(builder)
				.withPageable(pageRequest);

		final Page<Concept> concepts = elasticsearchTemplate.queryForPage(queryBuilder.build(), Concept.class);

		Map<String, Concept> conceptIdMap = new HashMap<>();
		for (Concept concept : concepts) {
			conceptIdMap.put(concept.getConceptId(), concept);
			concept.getDescriptions().clear();
			concept.getRelationships().clear();
		}

		Map<String, ConceptMini> conceptMiniMap = new HashMap<>();

		// Fetch Relationships
		queryBuilder.withQuery(boolQuery()
				.must(termsQuery("sourceId", conceptIdMap.keySet()))
				.must(branchCriteria))
				.withPageable(PAGE_REQUEST_LARGE); // FIXME: this is temporary
		final List<Relationship> relationships = elasticsearchTemplate.queryForList(queryBuilder.build(), Relationship.class);
		// Join Relationships
		for (Relationship relationship : relationships) {
			conceptIdMap.get(relationship.getSourceId()).addRelationship(relationship);
			relationship.setType(getConceptMini(conceptMiniMap, relationship.getTypeId()));
			relationship.setDestination(getConceptMini(conceptMiniMap, relationship.getDestinationId()));
		}

		// Fetch ConceptMini definition statuses
		queryBuilder.withQuery(boolQuery()
				.must(termsQuery("conceptId", conceptMiniMap.keySet()))
				.must(branchCriteria))
				.withPageable(PAGE_REQUEST_LARGE); // FIXME: this is temporary
		final List<Concept> conceptsForMini = elasticsearchTemplate.queryForList(queryBuilder.build(), Concept.class);
		for (Concept concept : conceptsForMini) {
			conceptMiniMap.get(concept.getConceptId()).setDefinitionStatusId(concept.getDefinitionStatusId());
		}

		fetchDescriptions(branchCriteria, conceptIdMap, conceptMiniMap);

		return concepts;
	}

	private void fetchDescriptions(QueryBuilder branchCriteria, Map<String, Concept> conceptIdMap, Map<String, ConceptMini> conceptMiniMap) {
		final NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder()
				.withPageable(PAGE_REQUEST_LARGE);

		// Fetch Descriptions
		final Set<String> allConceptIds = new HashSet<>();
		if (conceptIdMap != null) {
			allConceptIds.addAll(conceptIdMap.keySet());
		}
		if (conceptMiniMap != null) {
			allConceptIds.addAll(conceptMiniMap.keySet());
		}
		if (allConceptIds.isEmpty()) {
			return;
		}

		queryBuilder.withQuery(boolQuery()
				.must(branchCriteria))
				.withFilter(boolQuery().must(termsQuery("conceptId", allConceptIds)))
				.withPageable(PAGE_REQUEST_LARGE); // FIXME: this is temporary
		final Page<Description> descriptions = elasticsearchTemplate.queryForPage(queryBuilder.build(), Description.class);
		// Join Descriptions
		Map<String, Description> descriptionIdMap = new HashMap<>();
		for (Description description : descriptions) {
			descriptionIdMap.put(description.getDescriptionId(), description);
			final String descriptionConceptId = description.getConceptId();
			if (conceptIdMap != null) {
				final Concept concept = conceptIdMap.get(descriptionConceptId);
				if (concept != null) {
					concept.addDescription(description);
				}
			}
			if (conceptMiniMap != null) {
				final ConceptMini conceptMini = conceptMiniMap.get(descriptionConceptId);
				if (conceptMini != null && Concepts.FSN.equals(description.getTypeId()) && description.isActive()) {
					conceptMini.addActiveFsn(description);
				}
			}
		}

		// Fetch Lang Refset Members
		queryBuilder.withQuery(boolQuery()
				.must(branchCriteria)
				.must(termQuery("active", true)))
				.withFilter(boolQuery().must(termsQuery("referencedComponentId", descriptionIdMap.keySet())))
				.withPageable(PAGE_REQUEST_LARGE); // FIXME: this is temporary
		final Page<LanguageReferenceSetMember> langRefsetMembers = elasticsearchTemplate.queryForPage(queryBuilder.build(), LanguageReferenceSetMember.class);
		// Join Lang Refset Members
		for (LanguageReferenceSetMember langRefsetMember : langRefsetMembers) {
			descriptionIdMap.get(langRefsetMember.getReferencedComponentId())
					.addAcceptability(langRefsetMember.getRefsetId(), langRefsetMember.getAcceptabilityId());
		}
	}

	private ConceptMini getConceptMini(Map<String, ConceptMini> conceptMiniMap, String id) {
		ConceptMini mini = conceptMiniMap.get(id);
		if (mini == null) {
			mini = new ConceptMini(id);
			if (id != null) {
				conceptMiniMap.put(id, mini);
			}
		}
		return mini;
	}

	public Page<Description> findDescriptions(String path, String term, PageRequest pageRequest) {
		final QueryBuilder branchCriteria = versionControlHelper.getBranchCriteria(path);

		final BoolQueryBuilder builder = boolQuery()
				.must(branchCriteria);
		if (!Strings.isNullOrEmpty(term)) {
			builder.must(simpleQueryStringQuery(term).field("term"));
		}

		final NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder()
				.withQuery(builder)
				.withSort(SortBuilders.scoreSort())
				.withPageable(pageRequest);

		final NativeSearchQuery build = queryBuilder.build();
		return elasticsearchTemplate.queryForPage(build, Description.class);
	}

	public Concept create(Concept conceptVersion, String path) {
		final Branch branch = branchService.findBranchOrThrow(path);
		if (find(conceptVersion.getConceptId(), path) != null) {
			throw new IllegalArgumentException("Concept '" + conceptVersion.getConceptId() + "' already exists on branch '" + path + "'.");
		}
		return doSave(conceptVersion, branch);
	}

	public ReferenceSetMember create(ReferenceSetMember referenceSetMember, String path) {
		final Branch branch = branchService.findBranchOrThrow(path);
		if (find(referenceSetMember.getMemberId(), path) != null) {
			throw new IllegalArgumentException("Reference Set Member '" + referenceSetMember.getMemberId() + "' already exists on branch '" + path + "'.");
		}
		return doSave(referenceSetMember, branch);

	}

	public Concept update(Concept conceptVersion, String path) {
		final Branch branch = branchService.findBranchOrThrow(path);
		final String conceptId = conceptVersion.getConceptId();
		Assert.isTrue(!Strings.isNullOrEmpty(conceptId), "conceptId is required.");
		final Concept existingConcept = find(conceptId, path);
		if (existingConcept == null) {
			throw new IllegalArgumentException("Concept '" + conceptId + "' does not exist on branch '" + path + "'.");
		}

		recoverAndMarkDeletions(conceptVersion.getDescriptions(), existingConcept.getDescriptions());
		recoverAndMarkDeletions(conceptVersion.getRelationships(), existingConcept.getRelationships());

		return doSave(conceptVersion, branch);
	}

	private <C extends Component> void recoverAndMarkDeletions(Set<C> newComponents, Set<C> existingComponents) {
		for (C existingComponent : existingComponents) {
			if (!newComponents.contains(existingComponent)) {
				existingComponent.markDeleted();
				newComponents.add(existingComponent);
			}
		}
	}

	private Concept doSave(Concept concept, Branch branch) {
		final Commit commit = branchService.openCommit(branch.getFatPath());
		final Concept savedConcept = doSaveBatchConceptsAndComponents(Collections.singleton(concept), commit).iterator().next();
		branchService.completeCommit(commit);
		return savedConcept;
	}

	private ReferenceSetMember doSave(ReferenceSetMember member, Branch branch) {
		final Commit commit = branchService.openCommit(branch.getFatPath());
		final ReferenceSetMember savedMember = doSaveBatchMembers(Collections.singleton(member), commit).iterator().next();
		branchService.completeCommit(commit);
		return savedMember;
	}

	public Iterable<Concept> doSaveBatchConceptsAndComponents(Collection<Concept> concepts, Commit commit) {
		List<Description> descriptions = new ArrayList<>();
		List<Relationship> relationships = new ArrayList<>();
		for (Concept concept : concepts) {
			// Detach concept's components to be persisted separately
			descriptions.addAll(concept.getDescriptions());
			concept.getDescriptions().clear();
			relationships.addAll(concept.getRelationships());
			concept.getRelationships().clear();
		}

		final Iterable<Concept> conceptsSaved = doSaveBatchConcepts(concepts, commit);
		doSaveBatchDescriptions(descriptions, commit);
		doSaveBatchRelationships(relationships, commit);

		Map<String, Concept> conceptMap = new HashMap<>();
		for (Concept concept : concepts) {
			conceptMap.put(concept.getConceptId(), concept);
		}
		for (Description description : descriptions) {
			conceptMap.get(description.getConceptId()).addDescription(description);
		}
		for (Relationship relationship : relationships) {
			conceptMap.get(relationship.getSourceId()).addRelationship(relationship);
		}

		return conceptsSaved;
	}

	public Iterable<Concept> doSaveBatchConcepts(Collection<Concept> concepts, Commit commit) {
		if (!concepts.isEmpty()) {
			logger.info("Saving batch of {} concepts", concepts.size());
			final List<String> ids = concepts.stream().map(Concept::getConceptId).collect(Collectors.toList());
			versionControlHelper.endOldVersions(commit, "conceptId", Concept.class, ids, this.conceptRepository);
			versionControlHelper.setEntityMeta(concepts, commit);
			return conceptRepository.save(concepts);
		}
		return Collections.emptyList();
	}

	public void doSaveBatchDescriptions(Collection<Description> descriptions, Commit commit) {
		if (!descriptions.isEmpty()) {
			logger.info("Saving batch of {} descriptions", descriptions.size());
			final List<String> ids = descriptions.stream().map(Description::getDescriptionId).collect(Collectors.toList());
			versionControlHelper.endOldVersions(commit, "descriptionId", Description.class, ids, this.descriptionRepository);
			versionControlHelper.removeDeleted(descriptions);
			versionControlHelper.setEntityMeta(descriptions, commit);
			descriptionRepository.save(descriptions);
		}
	}

	public void doSaveBatchRelationships(Collection<Relationship> relationships, Commit commit) {
		if (!relationships.isEmpty()) {
			logger.info("Saving batch of {} relationships", relationships.size());
			final List<String> ids = relationships.stream().map(Relationship::getRelationshipId).collect(Collectors.toList());
			versionControlHelper.endOldVersions(commit, "relationshipId", Relationship.class, ids, this.relationshipRepository);
			versionControlHelper.removeDeleted(relationships);
			versionControlHelper.setEntityMeta(relationships, commit);
			relationshipRepository.save(relationships);
		}
	}

	public Iterable<ReferenceSetMember> doSaveBatchMembers(Collection<ReferenceSetMember> members, Commit commit) {
		if (!members.isEmpty()) {
			logger.info("Saving batch of {} members", members.size());
			final List<String> ids = members.stream().map(ReferenceSetMember::getMemberId).collect(Collectors.toList());
			versionControlHelper.endOldVersions(commit, "memberId", ReferenceSetMember.class, ids, this.referenceSetMemberRepository);
			versionControlHelper.setEntityMeta(members, commit);
			return referenceSetMemberRepository.save(members);
		}
		return Collections.emptyList();
	}

	public void postProcess(Commit commit) {
		queryIndexService.createTransitiveClosureForEveryConcept(commit);
	}

	public void deleteAll() {
		conceptRepository.deleteAll();
		descriptionRepository.deleteAll();
		relationshipRepository.deleteAll();
		referenceSetMemberRepository.deleteAll();
		queryIndexService.deleteAll();
	}
}
