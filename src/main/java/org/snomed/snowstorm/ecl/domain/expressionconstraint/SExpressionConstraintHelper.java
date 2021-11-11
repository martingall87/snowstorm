package org.snomed.snowstorm.ecl.domain.expressionconstraint;

import io.kaicode.elasticvc.api.BranchCriteria;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.snomed.snowstorm.core.data.domain.QueryConcept;
import org.snomed.snowstorm.core.data.services.QueryService;
import org.snomed.snowstorm.ecl.ConceptSelectorHelper;
import org.snomed.snowstorm.ecl.domain.RefinementBuilder;
import org.snomed.snowstorm.ecl.domain.RefinementBuilderImpl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class SExpressionConstraintHelper {

	// Used to force no match
	public static final String MISSING = "missing";
	public static final Long MISSING_LONG = 111L;

	private SExpressionConstraintHelper() {
	}

	protected static Optional<Page<Long>> select(SExpressionConstraint sExpressionConstraint, String path, BranchCriteria branchCriteria, boolean stated,
			Collection<Long> conceptIdFilter, PageRequest pageRequest, QueryService queryService) {

		BoolQueryBuilder query = ConceptSelectorHelper.getBranchAndStatedQuery(branchCriteria.getEntityBranchCriteria(QueryConcept.class), stated);
		RefinementBuilder refinementBuilder = new RefinementBuilderImpl(query, path, branchCriteria, stated, queryService);
		//The criteria of the ECL expression is here added to the refinement Builder, which may include inactive concepts
		Set<String> inactiveConcepts = new HashSet<>();
		sExpressionConstraint.addCriteria(refinementBuilder, inactiveConcepts);// This can add an inclusionFilter to the refinementBuilder.
		return Optional.of(ConceptSelectorHelper.fetchIds(query, conceptIdFilter, refinementBuilder.getInclusionFilter(), pageRequest, queryService));
	}

	protected static Optional<Page<Long>> select(SExpressionConstraint sExpressionConstraint, RefinementBuilder refinementBuilder) {
		return select(sExpressionConstraint, refinementBuilder.getPath(), refinementBuilder.getBranchCriteria(), refinementBuilder.isStated(), null, null, refinementBuilder.getQueryService());
	}

}
