package org.snomed.snowstorm.core.data.services;

import org.snomed.snowstorm.core.data.domain.*;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class Automerger {
    private enum ComponentType {
        Concept, Description, Relationship,
        ClassAxiom, GciAxiom, LanguageReferenceSetMember
    }

    /**
     * Construct a new instance of Concept which is a combination of both sourceConcept and targetConceptNew. The new, auto-merged
     * instance will be based on sourceConcept with edits belonging targetConceptNew applied on top. To find the edits belonging
     * to targetConceptNew, the instance will be compared against targetConceptOld. If any parameter given is null,
     * then a new, blank instance will be returned.
     *
     * @param sourceConcept    The state that will be the baseline for the new, auto-merged Concept.
     * @param targetConceptOld The state to help identify which edits belong to targetConceptNew.
     * @param targetConceptNew Edits belonging to this Concept will be re-applied to the new, auto-merged Concept.
     * @return A new instance of Concept which is a combination of both sourceConcept and targetConceptNew.
     */
    public Concept automerge(Concept sourceConcept, Concept targetConceptOld, Concept targetConceptNew) {
        if (sourceConcept == null || targetConceptOld == null || targetConceptNew == null) {
            return new Concept();
        }

        // Identify all components that have changed on targetConceptNew
        Map<ComponentType, Set<String>> componentsChangedOnTargetNew = getComponentsChangedOnTargetNew(targetConceptOld, targetConceptNew);

        /*
         * Strategy -->
         * Create a new Concept, cloned from sourceConcept. If an individual field (i.e. module etc) is different on
         * targetConceptNew compared to targetConceptOld, apply the difference to the newly created Concept. Effectively, re-apply
         * the changes on targetConceptNew to sourceConcept.
         * */
        return rebaseTargetChangesOntoSource(sourceConcept, targetConceptOld, targetConceptNew, componentsChangedOnTargetNew);
    }

    private Map<ComponentType, Set<String>> getComponentsChangedOnTargetNew(Concept targetConceptOld, Concept targetConceptNew) {
        Map<ComponentType, Set<String>> changesOnTargetConceptNew = new HashMap<>();

        // Has Concept changed?
        boolean conceptHasChanged = !Objects.equals(targetConceptOld.buildReleaseHash(), targetConceptNew.buildReleaseHash());
        if (conceptHasChanged) {
            changesOnTargetConceptNew.put(ComponentType.Concept, Set.of(targetConceptOld.getConceptId()));
        }

        // Have Descriptions changed?
        Map<String, SnomedComponent<?>> targetDescriptionsOld = mapByIdentifier(targetConceptOld.getDescriptions());
        Map<String, SnomedComponent<?>> targetDescriptionsNew = mapByIdentifier(targetConceptNew.getDescriptions());
        Set<String> diffInDescriptions = getComponentIdsChanged(targetDescriptionsOld, targetDescriptionsNew);
        if (!diffInDescriptions.isEmpty()) {
            changesOnTargetConceptNew.put(ComponentType.Description, diffInDescriptions);
        }

        // Have Relationships changed?
        Map<String, SnomedComponent<?>> targetRelationshipsOld = mapByIdentifier(targetConceptOld.getRelationships());
        Map<String, SnomedComponent<?>> targetRelationshipsNew = mapByIdentifier(targetConceptNew.getRelationships());
        Set<String> diffInRelationships = getComponentIdsChanged(targetRelationshipsOld, targetRelationshipsNew);
        if (!diffInRelationships.isEmpty()) {
            changesOnTargetConceptNew.put(ComponentType.Relationship, diffInRelationships);
        }

        // Have Axioms (Class) changed?
        Map<String, Axiom> targetClassAxiomsOld = mapAxiomByIdentifier(targetConceptOld.getClassAxioms());
        Map<String, Axiom> targetClassAxiomsNew = mapAxiomByIdentifier(targetConceptNew.getClassAxioms());
        Set<String> diffInClassAxioms = getAxiomIdsChanged(targetClassAxiomsOld, targetClassAxiomsNew);
        if (!diffInClassAxioms.isEmpty()) {
            changesOnTargetConceptNew.put(ComponentType.ClassAxiom, diffInClassAxioms);
        }

        // Have Axioms (GCI) changed?
        Map<String, Axiom> targetGCIAxiomsOld = mapAxiomByIdentifier(targetConceptOld.getGciAxioms());
        Map<String, Axiom> targetGCIAxiomsNew = mapAxiomByIdentifier(targetConceptNew.getGciAxioms());
        Set<String> diffInGCIAxioms = getAxiomIdsChanged(targetGCIAxiomsOld, targetGCIAxiomsNew);
        if (!diffInGCIAxioms.isEmpty()) {
            changesOnTargetConceptNew.put(ComponentType.GciAxiom, diffInGCIAxioms);
        }

        // Have ReferenceSetMembers (language) changed?
        Map<String, SnomedComponent<?>> targetLangMembersOld = mapLangMembersByIdentifier(targetConceptOld.getDescriptions());
        Map<String, SnomedComponent<?>> targetLangMembersNew = mapLangMembersByIdentifier(targetConceptNew.getDescriptions());
        Set<String> diffInLangMembers = getComponentIdsChanged(targetLangMembersOld, targetLangMembersNew);
        if (!diffInLangMembers.isEmpty()) {
            changesOnTargetConceptNew.put(ComponentType.LanguageReferenceSetMember, diffInLangMembers);
        }

        return changesOnTargetConceptNew;
    }

    private Map<String, SnomedComponent<?>> mapByIdentifier(Set<? extends SnomedComponent<?>> components) {
        Map<String, SnomedComponent<?>> mapByIdentifier = new HashMap<>();
        for (SnomedComponent<?> component : components) {
            mapByIdentifier.put(component.getId(), component);
        }

        return mapByIdentifier;
    }

    private Map<String, Axiom> mapAxiomByIdentifier(Set<Axiom> axioms) {
        Map<String, Axiom> mapByIdentifier = new HashMap<>();
        for (Axiom axiom : axioms) {
            mapByIdentifier.put(axiom.getId(), axiom);
        }

        return mapByIdentifier;
    }

    private Map<String, SnomedComponent<?>> mapLangMembersByIdentifier(Set<Description> descriptions) {
        Map<String, SnomedComponent<?>> mapByIdentifier = new HashMap<>();
        for (Description description : descriptions) {
            Set<ReferenceSetMember> langRefsetMembers = description.getLangRefsetMembers();
            for (ReferenceSetMember langRefsetMember : langRefsetMembers) {
                mapByIdentifier.put(langRefsetMember.getMemberId(), langRefsetMember);
            }
        }

        return mapByIdentifier;
    }

    private ReferenceSetMember getLangMemberFromDescriptions(Set<Description> descriptions, String langMemberId) {
        for (Description sourceDescription : descriptions) {
            for (ReferenceSetMember langRefsetMember : sourceDescription.getLangRefsetMembers()) {
                if (langMemberId.equals(langRefsetMember.getMemberId())) {
                    return langRefsetMember;
                }
            }
        }

        return null;
    }

    private Set<String> getComponentIdsChanged(Map<String, SnomedComponent<?>> before, Map<String, SnomedComponent<?>> after) {
        Set<String> identifiers = new HashSet<>();
        for (Map.Entry<String, SnomedComponent<?>> entrySet : after.entrySet()) {
            String key = entrySet.getKey();
            SnomedComponent<?> valueAfter = entrySet.getValue();
            SnomedComponent<?> valueBefore = before.get(key);

            if (valueBefore == null || !Objects.equals(valueBefore.buildReleaseHash(), valueAfter.buildReleaseHash())) {
                identifiers.add(valueAfter.getId());
            }
        }

        return identifiers;
    }

    private Set<String> getAxiomIdsChanged(Map<String, Axiom> before, Map<String, Axiom> after) {
        Set<String> identifiers = new HashSet<>();
        for (Map.Entry<String, Axiom> entrySet : after.entrySet()) {
            String key = entrySet.getKey();
            Axiom valueAfter = entrySet.getValue();
            Axiom valueBefore = before.get(key);

            if (valueBefore == null || !Objects.equals(valueBefore.getReferenceSetMember().buildReleaseHash(), valueAfter.getReferenceSetMember().buildReleaseHash()) || !Objects.equals(valueBefore.toString(), valueAfter.toString())) {
                identifiers.add(valueAfter.getId());
            }
        }

        return identifiers;
    }

    // Base auto-merged Concept on sourceConcept; edits on targetConceptNew will be re-applied.
    private Concept rebaseTargetChangesOntoSource(Concept sourceConcept, Concept targetConceptOld, Concept targetConceptNew, Map<ComponentType, Set<String>> changesOnTargetConceptNew) {
        Concept mergedConcept = new Concept();
        mergedConcept.clone(sourceConcept); // mergedConcept is deeply based on sourceConcept

        for (Map.Entry<ComponentType, Set<String>> entrySet : changesOnTargetConceptNew.entrySet()) {
            ComponentType changedComponentType = entrySet.getKey(); // e.g. => Descriptions
            Set<String> changedComponentIds = entrySet.getValue(); // e.g. => 101, 201, 301, 401, 501

            if (ComponentType.Concept.equals(changedComponentType)) {
                reapplyConceptChanges(sourceConcept, targetConceptNew, targetConceptOld, changedComponentIds, mergedConcept);
            } else if (ComponentType.Description.equals(changedComponentType)) {
                reapplyDescriptionChanges(mapByIdentifier(sourceConcept.getDescriptions()), mapByIdentifier(targetConceptOld.getDescriptions()), mapByIdentifier(targetConceptNew.getDescriptions()), changedComponentIds, mergedConcept);
            } else if (ComponentType.Relationship.equals(changedComponentType)) {
                reapplyRelationshipChanges(mapByIdentifier(sourceConcept.getRelationships()), mapByIdentifier(targetConceptOld.getRelationships()), mapByIdentifier(targetConceptNew.getRelationships()), changedComponentIds, mergedConcept);
            } else if (ComponentType.ClassAxiom.equals(changedComponentType)) {
                mergedConcept.setClassAxioms(reapplyAxiomsChanges(mapAxiomByIdentifier(sourceConcept.getClassAxioms()), mapAxiomByIdentifier(targetConceptOld.getClassAxioms()), mapAxiomByIdentifier(targetConceptNew.getClassAxioms()), changedComponentIds, mergedConcept));
            } else if (ComponentType.GciAxiom.equals(changedComponentType)) {
                mergedConcept.setGciAxioms(reapplyAxiomsChanges(mapAxiomByIdentifier(sourceConcept.getGciAxioms()), mapAxiomByIdentifier(targetConceptOld.getGciAxioms()), mapAxiomByIdentifier(targetConceptNew.getGciAxioms()), changedComponentIds, mergedConcept));
            } else if (ComponentType.LanguageReferenceSetMember.equals(changedComponentType)) {
                reapplyLangMemberChanges(mapLangMembersByIdentifier(sourceConcept.getDescriptions()), mapLangMembersByIdentifier(targetConceptOld.getDescriptions()), mapLangMembersByIdentifier(targetConceptNew.getDescriptions()), changedComponentIds, mergedConcept);
            }
        }

        return mergedConcept;
    }

    private void reapplyConceptChanges(Concept sourceConcept, Concept targetConceptNew, Concept targetConceptOld, Set<String> value, Concept mergedConcept) {
        mergedConcept.setActive(getValueChanged(targetConceptOld.isActive(), targetConceptNew.isActive(), sourceConcept.isActive()));
        mergedConcept.setModuleId(getValueChanged(targetConceptOld.getModuleId(), targetConceptNew.getModuleId(), sourceConcept.getModuleId()));
        mergedConcept.setDefinitionStatus(getValueChanged(targetConceptOld.getDefinitionStatus(), targetConceptNew.getDefinitionStatus(), sourceConcept.getDefinitionStatus()));
        mergedConcept.setReleaseHash(getValueChanged(targetConceptOld.getReleaseHash(), targetConceptNew.getReleaseHash(), sourceConcept.getReleaseHash()));
        mergedConcept.setReleased(getValueChanged(targetConceptOld.isReleased(), targetConceptNew.isReleased(), sourceConcept.isReleased()));

        mergedConcept.updateEffectiveTime();
    }

    private void reapplyDescriptionChanges(Map<String, SnomedComponent<?>> sourceDescriptions, Map<String, SnomedComponent<?>> targetDescriptionsOld, Map<String, SnomedComponent<?>> targetDescriptionsNew, Set<String> changedDescriptionIds, Concept mergedConcept) {
        Map<String, Description> mergedDescriptions = new HashMap<>();

        // Merge changed Descriptions
        for (String changedDescriptionId : changedDescriptionIds) {
            Description sourceDescription = (Description) sourceDescriptions.get(changedDescriptionId);
            Description targetDescriptionOld = (Description) targetDescriptionsOld.get(changedDescriptionId);
            Description targetDescriptionNew = (Description) targetDescriptionsNew.get(changedDescriptionId);
            Description mergedDescription = new Description();

            // Didn't exist before user started authoring; nothing to re-apply.
            if (sourceDescription == null || targetDescriptionOld == null) {
                mergedDescription = targetDescriptionNew;
            } else {
                mergedDescription.setDescriptionId(sourceDescription.getDescriptionId());
                mergedDescription.setConceptId(sourceDescription.getConceptId());
                mergedDescription.copyReleaseDetails(sourceDescription);

                mergedDescription.setActive(getValueChanged(targetDescriptionOld.isActive(), targetDescriptionNew.isActive(), sourceDescription.isActive()));
                mergedDescription.setModuleId(getValueChanged(targetDescriptionOld.getModuleId(), targetDescriptionNew.getModuleId(), sourceDescription.getModuleId()));
                mergedDescription.setTerm(getValueChanged(targetDescriptionOld.getTerm(), targetDescriptionNew.getTerm(), sourceDescription.getTerm()));
                mergedDescription.setCaseSignificanceId(getValueChanged(targetDescriptionOld.getCaseSignificanceId(), targetDescriptionNew.getCaseSignificanceId(), sourceDescription.getCaseSignificanceId()));
                mergedDescription.setLanguageRefsetMembers(sourceDescription.getLangRefsetMembers());

                // Re-apply immutable fields (legal as not yet released)
                if (sourceDescription.getReleasedEffectiveTime() == null) {
                    mergedDescription.setLanguageCode(getValueChanged(targetDescriptionOld.getLanguageCode(), targetDescriptionNew.getLanguageCode(), sourceDescription.getLanguageCode()));
                    mergedDescription.setTypeId(getValueChanged(targetDescriptionOld.getTypeId(), targetDescriptionNew.getTypeId(), sourceDescription.getTypeId()));
                } else {
                    mergedDescription.setLanguageCode(sourceDescription.getLanguageCode());
                    mergedDescription.setTypeId(sourceDescription.getTypeId());
                }
            }

            mergedDescription.updateEffectiveTime();
            mergedDescriptions.put(changedDescriptionId, mergedDescription);
        }

        // Merge unchanged Descriptions
        for (Map.Entry<String, SnomedComponent<?>> entrySet : sourceDescriptions.entrySet()) {
            String unchangedDescriptionId = entrySet.getKey();
            Description description = (Description) entrySet.getValue();

            // There are no target edits if the identifier is absent; therefore, take from source.
            mergedDescriptions.putIfAbsent(unchangedDescriptionId, description);
        }

        mergedConcept.setDescriptions(new HashSet<>(mergedDescriptions.values()));
    }

    private void reapplyRelationshipChanges(Map<String, SnomedComponent<?>> sourceRelationships, Map<String, SnomedComponent<?>> targetRelationshipsOld, Map<String, SnomedComponent<?>> targetRelationshipsNew, Set<String> changedRelationshipIds, Concept mergedConcept) {
        Map<String, Relationship> mergedRelationships = new HashMap<>();

        // Merge changed Relationships
        for (String changedRelationshipId : changedRelationshipIds) {
            Relationship sourceRelationship = (Relationship) sourceRelationships.get(changedRelationshipId);
            Relationship targetRelationshipOld = (Relationship) targetRelationshipsOld.get(changedRelationshipId);
            Relationship targetRelationshipNew = (Relationship) targetRelationshipsNew.get(changedRelationshipId);
            Relationship mergedRelationship = new Relationship();

            // Didn't exist before user started authoring; nothing to re-apply.
            if (sourceRelationship == null || targetRelationshipOld == null) {
                mergedRelationship = targetRelationshipNew;
            } else {
                mergedRelationship.setRelationshipId(sourceRelationship.getRelationshipId());
                mergedRelationship.copyReleaseDetails(sourceRelationship);

                mergedRelationship.setActive(getValueChanged(targetRelationshipOld.isActive(), targetRelationshipNew.isActive(), sourceRelationship.isActive()));
                mergedRelationship.setModuleId(getValueChanged(targetRelationshipOld.getModuleId(), targetRelationshipNew.getModuleId(), sourceRelationship.getModuleId()));
                mergedRelationship.setRelationshipGroup(getValueChanged(targetRelationshipOld.getRelationshipGroup(), targetRelationshipNew.getRelationshipGroup(), sourceRelationship.getRelationshipGroup()));
                mergedRelationship.setCharacteristicTypeId(getValueChanged(targetRelationshipOld.getCharacteristicTypeId(), targetRelationshipNew.getCharacteristicTypeId(), sourceRelationship.getCharacteristicTypeId()));
                mergedRelationship.setModifier(getValueChanged(targetRelationshipOld.getModifier(), targetRelationshipNew.getModifier(), sourceRelationship.getModifier()));

                // Re-apply immutable fields (legal as not yet released)
                if (sourceRelationship.getReleasedEffectiveTime() == null) {
                    mergedRelationship.setSourceId(getValueChanged(targetRelationshipOld.getSourceId(), targetRelationshipNew.getSourceId(), sourceRelationship.getSourceId()));

                    if (targetRelationshipNew.getDestinationId() != null) {
                        mergedRelationship.setDestinationId(getValueChanged(targetRelationshipOld.getDestinationId(), targetRelationshipNew.getDestinationId(), sourceRelationship.getDestinationId()));
                    } else {
                        mergedRelationship.setValue(getValueChanged(targetRelationshipOld.getValue(), targetRelationshipNew.getValue(), sourceRelationship.getValue()));
                    }

                    mergedRelationship.setTypeId(getValueChanged(targetRelationshipOld.getTypeId(), targetRelationshipNew.getTypeId(), sourceRelationship.getTypeId()));
                } else {
                    mergedRelationship.setSourceId(sourceRelationship.getSourceId());

                    if (targetRelationshipNew.getDestinationId() != null) {
                        mergedRelationship.setDestinationId(sourceRelationship.getDestinationId());
                    } else {
                        mergedRelationship.setValue(sourceRelationship.getValue());
                    }

                    mergedRelationship.setTypeId(sourceRelationship.getTypeId());
                }
            }

            mergedRelationship.updateEffectiveTime();
            mergedRelationships.put(changedRelationshipId, mergedRelationship);
        }

        // Merge unchanged Relationships
        for (Map.Entry<String, SnomedComponent<?>> entrySet : sourceRelationships.entrySet()) {
            String unchangedRelationshipId = entrySet.getKey();
            Relationship relationship = (Relationship) entrySet.getValue();

            // There are no target edits if the identifier is absent; therefore, take from source.
            mergedRelationships.putIfAbsent(unchangedRelationshipId, relationship);
        }

        mergedConcept.setRelationships(new HashSet<>(mergedRelationships.values()));
    }

    private Set<Axiom> reapplyAxiomsChanges(Map<String, Axiom> sourceAxioms, Map<String, Axiom> targetAxiomsOld, Map<String, Axiom> targetAxiomsNew, Set<String> changedAxiomIds, Concept mergedConcept) {
        Map<String, Axiom> mergedAxioms = new HashMap<>();

        // Merge changed Axioms
        for (String changedAxiomId : changedAxiomIds) {
            Axiom sourceAxiom = sourceAxioms.get(changedAxiomId);
            Axiom targetAxiomOld = targetAxiomsOld.get(changedAxiomId);
            Axiom targetAxiomNew = targetAxiomsNew.get(changedAxiomId);
            Axiom mergedAxiom = new Axiom();
            ReferenceSetMember mergedReferenceSetMember = new ReferenceSetMember();

            // Didn't exist before user started authoring; nothing to re-apply.
            if (sourceAxiom == null || targetAxiomOld == null) {
                mergedAxiom = targetAxiomNew;
                mergedReferenceSetMember = targetAxiomNew.getReferenceSetMember();
            } else {
                // Re-apply fields on Axiom
                mergedAxiom.setAxiomId(sourceAxiom.getAxiomId());
                mergedAxiom.setActive(getValueChanged(targetAxiomOld.isActive(), targetAxiomNew.isActive(), sourceAxiom.isActive()));
                mergedAxiom.setReleased(getValueChanged(targetAxiomOld.isReleased(), targetAxiomNew.isReleased(), sourceAxiom.isReleased()));
                mergedAxiom.setDefinitionStatusId(getValueChanged(targetAxiomOld.getDefinitionStatusId(), targetAxiomNew.getDefinitionStatusId(), sourceAxiom.getDefinitionStatusId()));
                mergedAxiom.setModuleId(getValueChanged(targetAxiomOld.getModuleId(), targetAxiomNew.getModuleId(), sourceAxiom.getModuleId()));
                if (targetAxiomOld.getRelationships().equals(targetAxiomNew.getRelationships())) {
                    mergedAxiom.setRelationships(targetAxiomNew.getRelationships());
                } else {
                    mergedAxiom.setRelationships(targetAxiomOld.getRelationships());
                }

                // Re-apply fields on ReferenceSetMember
                ReferenceSetMember sourceReferenceSetMember = sourceAxiom.getReferenceSetMember();
                ReferenceSetMember targetReferenceSetMemberOld = targetAxiomOld.getReferenceSetMember();
                ReferenceSetMember targetReferenceSetMemberNew = targetAxiomNew.getReferenceSetMember();

                mergedReferenceSetMember.setMemberId(sourceReferenceSetMember.getMemberId());
                mergedReferenceSetMember.setReleased(sourceReferenceSetMember.isReleased());
                mergedReferenceSetMember.setReleaseHash(sourceReferenceSetMember.getReleaseHash());
                mergedReferenceSetMember.setReleasedEffectiveTime(sourceReferenceSetMember.getReleasedEffectiveTime());
                mergedReferenceSetMember.setEffectiveTimeI(getValueChanged(targetReferenceSetMemberOld.getEffectiveTimeI(), targetReferenceSetMemberNew.getEffectiveTimeI(), sourceReferenceSetMember.getEffectiveTimeI()));

                mergedReferenceSetMember.setReferencedComponentId(sourceReferenceSetMember.getReferencedComponentId());
                mergedReferenceSetMember.setActive(getValueChanged(targetReferenceSetMemberOld.isActive(), targetReferenceSetMemberNew.isActive(), sourceReferenceSetMember.isActive()));
                mergedReferenceSetMember.setReleased(getValueChanged(targetReferenceSetMemberOld.isReleased(), targetReferenceSetMemberNew.isReleased(), sourceReferenceSetMember.isReleased()));
                mergedReferenceSetMember.setRefsetId(getValueChanged(targetReferenceSetMemberOld.getRefsetId(), targetReferenceSetMemberNew.getRefsetId(), sourceReferenceSetMember.getRefsetId()));
                mergedReferenceSetMember.setModuleId(getValueChanged(targetReferenceSetMemberOld.getModuleId(), targetReferenceSetMemberNew.getModuleId(), sourceReferenceSetMember.getModuleId()));
                mergedReferenceSetMember.setAdditionalField(
                        ReferenceSetMember.OwlExpressionFields.OWL_EXPRESSION,
                        getValueChanged(
                                targetReferenceSetMemberOld.getAdditionalField(ReferenceSetMember.OwlExpressionFields.OWL_EXPRESSION),
                                targetReferenceSetMemberNew.getAdditionalField(ReferenceSetMember.OwlExpressionFields.OWL_EXPRESSION),
                                sourceReferenceSetMember.getAdditionalField(ReferenceSetMember.OwlExpressionFields.OWL_EXPRESSION)
                        )
                );
            }

            mergedReferenceSetMember.updateEffectiveTime();
            mergedAxiom.setReferenceSetMember(mergedReferenceSetMember);
            mergedAxioms.put(changedAxiomId, mergedAxiom);
        }

        // Merge unchanged Axioms
        for (Map.Entry<String, Axiom> entrySet : sourceAxioms.entrySet()) {
            String unchangedAxiomId = entrySet.getKey();
            Axiom axiom = entrySet.getValue();

            // There are no target edits if the identifier is absent; therefore, take from source.
            mergedAxioms.putIfAbsent(unchangedAxiomId, axiom);
        }

        return new HashSet<>(mergedAxioms.values());
    }

    private void reapplyLangMemberChanges(Map<String, SnomedComponent<?>> sourceLangMembers, Map<String, SnomedComponent<?>> targetLangMembersOld, Map<String, SnomedComponent<?>> targetLangMembersNew, Set<String> changedLangMemberIds, Concept mergedConcept) {
        Map<String, ReferenceSetMember> mergedReferenceSetMembers = new HashMap<>();

        // Merge changed ReferenceSetMembers
        for (String changedLangMemberId : changedLangMemberIds) {
            ReferenceSetMember sourceReferenceSetMember = (ReferenceSetMember) sourceLangMembers.get(changedLangMemberId);
            ReferenceSetMember targetReferenceSetMemberOld = (ReferenceSetMember) targetLangMembersOld.get(changedLangMemberId);
            ReferenceSetMember targetReferenceSetMemberNew = (ReferenceSetMember) targetLangMembersNew.get(changedLangMemberId);
            ReferenceSetMember mergedReferenceSetMember = new ReferenceSetMember();

            // Didn't exist before user started authoring; nothing to re-apply.
            if (sourceReferenceSetMember == null || targetReferenceSetMemberOld == null) {
                mergedReferenceSetMember = targetReferenceSetMemberNew;
            } else {
                mergedReferenceSetMember.setMemberId(sourceReferenceSetMember.getMemberId());
                mergedReferenceSetMember.setReferencedComponentId(sourceReferenceSetMember.getReferencedComponentId());
                mergedReferenceSetMember.setConceptId(sourceReferenceSetMember.getConceptId());
                mergedReferenceSetMember.copyReleaseDetails(sourceReferenceSetMember);

                // Re-apply immutable fields (legal as not yet released)
                if (sourceReferenceSetMember.getReleasedEffectiveTime() == null) {
                    mergedReferenceSetMember.setRefsetId(getValueChanged(targetReferenceSetMemberOld.getRefsetId(), targetReferenceSetMemberNew.getRefsetId(), sourceReferenceSetMember.getRefsetId()));
                    mergedReferenceSetMember.setAdditionalField(
                            ReferenceSetMember.LanguageFields.ACCEPTABILITY_ID,
                            getValueChanged(
                                    targetReferenceSetMemberOld.getAdditionalField(ReferenceSetMember.LanguageFields.ACCEPTABILITY_ID),
                                    targetReferenceSetMemberNew.getAdditionalField(ReferenceSetMember.LanguageFields.ACCEPTABILITY_ID),
                                    sourceReferenceSetMember.getAdditionalField(ReferenceSetMember.LanguageFields.ACCEPTABILITY_ID)
                            )
                    );
                } else {
                    mergedReferenceSetMember.setRefsetId(sourceReferenceSetMember.getRefsetId());
                    mergedReferenceSetMember.setAdditionalField(ReferenceSetMember.LanguageFields.ACCEPTABILITY_ID, sourceReferenceSetMember.getAdditionalField(ReferenceSetMember.LanguageFields.ACCEPTABILITY_ID));
                }

                // Re-apply mutable fields
                mergedReferenceSetMember.setActive(getValueChanged(targetReferenceSetMemberOld.isActive(), targetReferenceSetMemberNew.isActive(), sourceReferenceSetMember.isActive()));
                mergedReferenceSetMember.setModuleId(getValueChanged(targetReferenceSetMemberOld.getModuleId(), targetReferenceSetMemberNew.getModuleId(), sourceReferenceSetMember.getModuleId()));
            }

            mergedReferenceSetMember.updateEffectiveTime();
            mergedReferenceSetMembers.put(changedLangMemberId, mergedReferenceSetMember);
        }

        // Re-populate Description's LanguageReferenceSetMembers
        for (Description description : mergedConcept.getDescriptions()) {
            String descriptionId = description.getDescriptionId();
            Map<String, ReferenceSetMember> languageReferenceSetMembers = new HashMap<>();

            // Replace existing with newly merged
            for (ReferenceSetMember referenceSetMember : description.getLangRefsetMembers()) {
                String memberId = referenceSetMember.getMemberId();
                if (changedLangMemberIds.contains(memberId)) {
                    languageReferenceSetMembers.put(memberId, mergedReferenceSetMembers.get(memberId));
                    mergedReferenceSetMembers.remove(memberId);
                } else {
                    languageReferenceSetMembers.put(memberId, referenceSetMember);
                }
            }

            // Add newly created
            for (ReferenceSetMember referenceSetMember : mergedReferenceSetMembers.values()) {
                String memberId = referenceSetMember.getMemberId();
                String referencedComponentId = referenceSetMember.getReferencedComponentId();

                // Keep language associated with correct Description
                if (referencedComponentId.equals(descriptionId)) {
                    languageReferenceSetMembers.putIfAbsent(memberId, mergedReferenceSetMembers.get(memberId));
                }
            }

            description.setLanguageRefsetMembers(languageReferenceSetMembers.values());
        }
    }

    // valueDefault is coming from Source and may have newer/different value than valueOld.
    private String getValueChanged(String valueOld, String valueNew, String valueDefault) {
        boolean valueHasChanged = !Objects.equals(valueOld, valueNew);
        if (valueHasChanged) {
            return valueNew;
        } else {
            return valueDefault;
        }
    }

    // valueDefault is coming from Source and may have newer/different value than valueOld.
    private Boolean getValueChanged(Boolean valueOld, Boolean valueNew, Boolean valueDefault) {
        boolean valueHasChanged = !Objects.equals(valueOld, valueNew);
        if (valueHasChanged) {
            return valueNew;
        } else {
            return valueDefault;
        }
    }

    // valueDefault is coming from Source and may have newer/different value than valueOld.
    private Integer getValueChanged(Integer valueOld, Integer valueNew, Integer valueDefault) {
        boolean valueHasChanged = !Objects.equals(valueOld, valueNew);
        if (valueHasChanged) {
            return valueNew;
        } else {
            return valueDefault;
        }
    }
}
