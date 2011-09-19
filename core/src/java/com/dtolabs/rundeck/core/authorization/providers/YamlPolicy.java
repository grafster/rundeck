/*
 * Copyright 2011 DTO Labs, Inc. (http://dtolabs.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

/*
* YamlPolicy.java
* 
* User: Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
* Created: 8/25/11 11:25 AM
* 
*/
package com.dtolabs.rundeck.core.authorization.providers;

import com.dtolabs.rundeck.core.authorization.Attribute;
import com.dtolabs.rundeck.core.authorization.Explanation;
import com.dtolabs.rundeck.core.utils.Converter;
import com.dtolabs.rundeck.core.utils.PairImpl;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.Predicate;
import org.apache.commons.collections.PredicateUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YamlPolicy implements a policy from a yaml document input or map.
 *
 * @author Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
 */
final class YamlPolicy implements Policy {

    public static final String TYPE_PROPERTY = "type";
    public static final String FOR_SECTION = "for";
    public static final String JOB_TYPE = "job";
    public static final String RULES_SECTION = "rules";
    public static final String ACTIONS_SECTION = "actions";
    public static final String CONTEXT_SECTION = "context";
    public Map policyInput;

    private Set<String> usernames = new HashSet<String>();
    private Set<Object> groups = new HashSet<Object>();
    AclContext aclContext;


    public YamlPolicy(final Map yamlDoc) {
        policyInput = yamlDoc;
        parseByClause();
        createAclContext();
    }

    public Set<String> getUsernames() {
        return usernames;
    }

    private EnvironmentalContext environment;
    private boolean envchecked;

    public EnvironmentalContext getEnvironment() {
        synchronized (this) {
            if (!envchecked) {
                //create
                final Object ctxClause = policyInput.get(CONTEXT_SECTION);
                if (null != ctxClause && ctxClause instanceof Map) {
                    Map ctx = (Map) ctxClause;

                    environment = new YamlEnvironmentalContext(EnvironmentalContext.URI_BASE, ctx);
                }
                envchecked = true;
            }
        }
        return environment;
    }
    static class YamlEnvironmentalContext implements EnvironmentalContext{
        Map<URI, String> matcher = new HashMap<URI, String>();

        YamlEnvironmentalContext(final String uriPrefix, final Map ctx) {
            for (final Object o : ctx.entrySet()) {
                Map.Entry entry=(Map.Entry) o;
                if(entry.getKey() instanceof String){
                    String path=(String) entry.getKey();
                    if(entry.getValue() instanceof String) {
                        String value = (String) entry.getValue();
                        final URI uri;
                        try {
                            uri = new URI(uriPrefix + path);
                        } catch (URISyntaxException e) {
                            continue;
                        }
                        matcher.put(uri, value);
                    }
                }
            }
        }

        public boolean matches(final Set<Attribute> environment) {
            //return true if all declared environmental context attributes match in the input
            Set<URI> matchedSet = new HashSet<URI>();
            for (final Attribute attribute : environment) {
                final String matchValue = matcher.get(attribute.property);
                if (null != matchValue) {
                    final RegexPredicate regexPredicate = new RegexPredicate(Pattern.compile(matchValue));
                    if (regexPredicate.evaluate(attribute.value)) {
                        matchedSet.add(attribute.property);
                    }
                }
            }

            return matchedSet.size()==matcher.keySet().size();
        }
    }

    public Set<Object> getGroups() {
        return groups;
    }

    public AclContext getContext() {

        return aclContext;
    }

    private void createAclContext() {
        aclContext = new YamlAclContext(policyInput, new TypeContextFactory() {
            public AclContext createAclContext(List typeSection) {
                return new TypeContext(createTypeRules(typeSection));
            }
        }, new LegacyContextFactory() {
            public AclContext createLegacyContext(Map rules) {
                return new LegacyRulesContext(rules);
            }
        }
        );
    }

    static interface LegacyContextFactory {
        public AclContext createLegacyContext(final Map rules);
    }

    static interface TypeContextFactory {
        public AclContext createAclContext(final List typeSection);
    }


    List<ContextMatcher> createTypeRules(final List typeSection) {
        final ArrayList<ContextMatcher> rules = new ArrayList<ContextMatcher>();
        int i = 1;
        for (final Object o : typeSection) {
            final Map section = (Map) o;
            rules.add(createTypeRuleContext(section, i));
            i++;
        }
        return rules;
    }

    /**
     * Create acl context  for specific rule in a type context
     */
    ContextMatcher createTypeRuleContext(final Map section, final int i) {
        return new TypeRuleContextMatcher(section, i);
    }

    private static String createLegacyJobResourcePath(final Map<String, String> resource) {
        return resource.get("group") + "/" + resource.get("job");
    }


    /**
     * parse the by: clause, allow single string or list of strings for username and grop values
     */
    private void parseByClause() {
        final Object byClause = policyInput.get("by");
        if (byClause == null) {
            return;
        }
        if (!(byClause instanceof Map)) {
            return;
        }
        final Map by = (Map) byClause;

        final Object u = by.get("username");
        final Object g = by.get("group");

        if (null != u) {
            if (u instanceof String) {
                usernames.add((String) u);
            } else if (u instanceof Collection) {
                for (final Object o : (Collection) u) {
                    if (o instanceof String) {
                        usernames.add((String) o);
                    }
                }
            }
        }

        if (null != g) {
            if (g instanceof String) {
                groups.add(g);
            } else if (g instanceof Collection) {
                for (final Object o : (Collection) g) {
                    if (o instanceof String) {
                        groups.add(o);
                    }
                }
            }
        }

    }

    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer("YamlPolicy[id:");
        sb.append(policyInput.get("id")).append(", groups:");
        for (final Object group : getGroups()) {
            sb.append(group.toString()).append(" ");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Produces decision for a resource, from a list of context matcher rules. if any matching rule produces a
     * REJECTED_DENIED result, then the decision is REJECTED_DENIED.  Otherwise if any rule produces a GRANTED decision,
     * the decision is GRANTED. Otherwise the decision is REJECTED.
     */
    static class TypeContext implements AclContext {
        private final List<ContextMatcher> typeRules;

        public TypeContext(final List<ContextMatcher> typeRules) {
            this.typeRules = typeRules;
        }

        public ContextDecision includes(final Map<String, String> resource, final String action) {
            final ArrayList<ContextEvaluation> evaluations = new ArrayList<ContextEvaluation>();
            boolean allowed = false;
            boolean denied = false;
            ContextEvaluation deniedEvaluation;
            for (final ContextMatcher matcher : typeRules) {
                final MatchedContext matched = matcher.includes(resource, action);
                if (!matched.isMatched()) {
                    //indicates the section did not match
                    continue;
                }
                final ContextDecision decision = matched.getDecision();
                if (decision.granted()) {
                    allowed = true;
                }
                if (Explanation.Code.REJECTED_DENIED == decision.getCode()) {
                    denied = true;
                }
                evaluations.addAll(decision.getEvaluations());
                if (!denied) {
                    for (final ContextEvaluation contextEvaluation : decision.getEvaluations()) {
                        if (Explanation.Code.REJECTED_DENIED == contextEvaluation.id) {
                            deniedEvaluation = contextEvaluation;
                            denied = true;
                            break;
                        }
                    }
                }
            }
            return new ContextDecision(denied ? Explanation.Code.REJECTED_DENIED
                                              : allowed ? Explanation.Code.GRANTED : Explanation.Code.REJECTED,
                allowed && !denied, evaluations);

        }
    }

    /**
     * Represents a match result with a decision result,
     */
    static class MatchedContext extends PairImpl<Boolean, ContextDecision> {
        MatchedContext(final Boolean matched, final ContextDecision decision) {
            super(matched, decision);
        }

        /**
         * Returns true if the context matched
         */
        public Boolean isMatched() {
            return getFirst();
        }

        /**
         * Returns the decision result
         */
        public ContextDecision getDecision() {
            return getSecond();
        }
    }

    static interface ContextMatcher {
        public MatchedContext includes(Map<String, String> resource, String action);
    }

    /**
     * returns an allow/reject decision for a specific rule within a type section, can return null indicating there was
     * no match. Format:
     * <pre>
     *     match:
     *       key: regex
     *       key2: [regexa, regexb]
     *     equals:
     *       key: value
     *     contains:
     *       key: value
     *       key2: [value1,value2]
     *     allow: action
     *     # or
     *     allow: [action1,action2]
     *     deny: action
     *     #or
     *     deny: [action1,action2]
     * </pre>
     */
    static class TypeRuleContextMatcher implements ContextMatcher {
        public static final String MATCH_SECTION = "match";
        public static final String EQUALS_SECTION = "equals";
        public static final String CONTAINS_SECTION = "contains";
        public static final String ALLOW_ACTIONS = "allow";
        public static final String DENY_ACTIONS = "deny";
        Map ruleSection;
        int index;


        TypeRuleContextMatcher(final Map ruleSection, final int index) {
            this.ruleSection = ruleSection;
            this.index = index;
        }

        @Override
        public String toString() {
            return "[rule: " + index + ": " + ruleSection + "]";
        }

        private static ConcurrentHashMap<String, Pattern> patternCache = new ConcurrentHashMap<String, Pattern>();

        private Pattern patternForRegex(final String regex) {
            if (!patternCache.containsKey(regex)) {
                patternCache.putIfAbsent(regex, Pattern.compile(regex));
            }
            return patternCache.get(regex);
        }

        public MatchedContext includes(final Map<String, String> resource, final String action) {
            final List<ContextEvaluation> evaluations = new ArrayList<ContextEvaluation>();

            if (!matchesRuleSections(resource, evaluations)) {
                return new MatchedContext(false, new ContextDecision(Explanation.Code.REJECTED, false, evaluations));
            }
            return new MatchedContext(true, evaluateActions(action, evaluations));
        }

        ContextDecision evaluateActions(final String action, final List<ContextEvaluation> evaluations) {
            //evaluate actions
            boolean denied = false;

            if (ruleSection.containsKey(DENY_ACTIONS)) {
                final HashSet<String> actions = new HashSet<String>();
                final Object actionsObj = ruleSection.get(DENY_ACTIONS);
                if (actionsObj instanceof String) {
                    final String actionStr = (String) actionsObj;
                    actions.add(actionStr);
                } else if (actionsObj instanceof List) {
                    actions.addAll((List<String>) actionsObj);
                } else {
                    evaluations.add(new ContextEvaluation(Explanation.Code.REJECTED_CONTEXT_EVALUATION_ERROR,
                        "Invalid action type."));
                }
                if (0 == actions.size()) {
                    //xxx:warn
                } else if (actions.contains("*") || actions.contains(action)) {
                    evaluations.add(new ContextEvaluation(Explanation.Code.REJECTED_DENIED,
                        this + " for actions: " + actions));
                    denied = true;
                }
            }
            if (denied) {
                return new ContextDecision(Explanation.Code.REJECTED_DENIED, false, evaluations);
            }
            boolean allowed = false;
            if (ruleSection.containsKey(ALLOW_ACTIONS)) {
                final HashSet<String> actions = new HashSet<String>();
                final Object actionsObj = ruleSection.get(ALLOW_ACTIONS);
                if (actionsObj instanceof String) {
                    final String actionStr = (String) actionsObj;
                    actions.add(actionStr);
                } else if (actionsObj instanceof List) {
                    actions.addAll((List<String>) actionsObj);
                } else {
                    evaluations.add(new ContextEvaluation(Explanation.Code.REJECTED_CONTEXT_EVALUATION_ERROR,
                        "Invalid action type."));
                }
                if (0 == actions.size()) {
                    //xxx:warn
                } else if (actions.contains("*") || actions.contains(action)) {
                    evaluations.add(new ContextEvaluation(Explanation.Code.GRANTED_ACTIONS_AND_COMMANDS_MATCHED,
                        this + " for actions: " + actions));
                    allowed = true;
                }
            }

            if (allowed) {
                return new ContextDecision(Explanation.Code.GRANTED_ACTIONS_AND_COMMANDS_MATCHED, true, evaluations);
            } else {
                return new ContextDecision(Explanation.Code.REJECTED, false, evaluations);
            }
        }

        /**
         * Return true if all of the defined rule sections match for the specified resource. If no rule sections exist,
         * then the result is true.
         */
        boolean matchesRuleSections(final Map<String, String> resource, final List<ContextEvaluation> evaluations) {
            int matchesRequired = 0;
            int matchesMet = 0;
            //evaluate match:
            if (ruleSection.containsKey(MATCH_SECTION)) {
                matchesRequired++;
                if (ruleMatchesMatchSection(resource, this.ruleSection)) {
                    matchesMet++;
                } else {
                    evaluations.add(new ContextEvaluation(Explanation.Code.REJECTED,
                        MATCH_SECTION + " section did not match"));
                }
            }
            //evaluate equals:
            if (ruleSection.containsKey(EQUALS_SECTION)) {
                matchesRequired++;
                if (ruleMatchesEqualsSection(resource, this.ruleSection)) {
                    matchesMet++;
                } else {
                    evaluations.add(new ContextEvaluation(Explanation.Code.REJECTED,
                        EQUALS_SECTION + " section did not match"));
                }
            }

            //evaluate contains:
            if (ruleSection.containsKey(CONTAINS_SECTION)) {
                matchesRequired++;
                if (ruleMatchesContainsSection(resource, this.ruleSection)) {
                    matchesMet++;
                } else {
                    evaluations.add(new ContextEvaluation(Explanation.Code.REJECTED,
                        CONTAINS_SECTION + " section did not match"));
                }
            }
            return matchesMet == matchesRequired;
        }

        boolean ruleMatchesContainsSection(final Map<String, String> resource, final Map ruleSection) {
            final Map section = (Map) ruleSection.get(CONTAINS_SECTION);
            return predicateMatchRules(section, resource, true, new Converter<String, Predicate>() {
                public Predicate convert(final String o) {
                    return new SetContainsPredicate(o);
                }
            });
        }

        boolean ruleMatchesEqualsSection(final Map<String, String> resource, final Map ruleSection) {
            final Map section = (Map) ruleSection.get(EQUALS_SECTION);
            return predicateMatchRules(section, resource, false, new Converter<String, Predicate>() {
                public Predicate convert(final String o) {
                    return PredicateUtils.equalPredicate(o);
                }
            });
        }

        boolean ruleMatchesMatchSection(final Map<String, String> resource, final Map ruleSection) {
            final Map section = (Map) ruleSection.get(MATCH_SECTION);
            return predicateMatchRules(section, resource, true, new Converter<String, Predicate>() {
                public Predicate convert(final String o) {
                    return new RegexPredicate(patternForRegex(o));
                }
            });
        }


        /**
         * Return true if all entries in the "match" map pass the predicate tests for the resource
         *
         * @param match                the set of matches to check
         * @param resource             the resource
         * @param allowListMatch       if true, allow the match value to be a list of values which much all pass the
         *                             test
         * @param predicateTransformer transformer to convert a String into a Predicate check
         */
        @SuppressWarnings ("rawtypes")
        boolean predicateMatchRules(final Map match, final Map<String, String> resource, final boolean allowListMatch,
                                    final Converter<String, Predicate> predicateTransformer) {
            for (final Object o : match.entrySet()) {
                final Map.Entry entry = (Map.Entry) o;
                final String key = (String) entry.getKey();
                final Object test = entry.getValue();

                final boolean matched = applyTest(resource, allowListMatch, predicateTransformer, key, test);
                if (!matched) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Return true if all predicate tests on a certain resource entry evaluate to true
         *
         * @param resource             the resource
         * @param allowListMatch       if true, allow the test to be a list of strings
         * @param predicateTransformer a Converter<S,Predicate> to convert String to Predicate test
         * @param key                  the resource attribute key to check
         * @param test                 test to apply, can be a String, or List of Strings if allowListMatch is true
         */
        boolean applyTest(final Map<String, String> resource, final boolean allowListMatch,
                          final Converter<String, Predicate> predicateTransformer, final String key,
                          final Object test) {

            final ArrayList<Predicate> tests = new ArrayList<Predicate>();
            if (allowListMatch && test instanceof List) {
                //must match all values
                for (final Object item : (List) test) {
                    final String s = (String) item;
                    tests.add(predicateTransformer.convert(s));
                }
            } else if (test instanceof String) {
                //match single test
                tests.add(predicateTransformer.convert((String) test));
            } else {
                //xxx:warn
                //unexpected format, do not match
                return false;
            }

            return PredicateUtils.allPredicate(tests).evaluate(resource.get(key));
        }

    }

    /**
     * evaluates to true if the input matches a regular expression
     */
    static class RegexPredicate implements Predicate {
        Pattern regex;

        RegexPredicate(final Pattern regex) {
            this.regex = regex;
        }

        public boolean evaluate(final Object o) {
            return o instanceof String && regex.matcher((String) o).matches();
        }

    }

    /**
     * Evaluates to true if the input is a string or collection of strings, and they are a superset of this object's
     * collection.
     */
    static class SetContainsPredicate implements Predicate {
        HashSet<String> items = new HashSet<String>();

        SetContainsPredicate(final Object item) {
            if (item instanceof String) {
                items.add((String) item);
            } else if (item instanceof List) {
                items.addAll((List<String>) item);
            } else {
                //xxx:warn
                //unexpected, will reject everything
                items = null;
            }
        }

        public boolean evaluate(final Object o) {
            if (null == items || null == o) {
                return false;
            }
            final Collection input;
            if (o instanceof String) {
                final HashSet<String> hs = new HashSet<String>();
                //treat o as comma-seperated list of strings
                final String str = (String) o;
                final String[] split = str.split(",");
                for (final String s : split) {
                    hs.add(s.trim());
                }
                input = hs;
            } else if (o instanceof Collection) {
                input = (Collection) o;
            } else {
                return false;
            }
            return CollectionUtils.isSubCollection(items, input);
        }
    }

    /**
     * Makes a decision for a job resource based on the "rules: " section
     */
    static class LegacyRulesContext implements AclContext {
        private final Map rules;

        private ConcurrentHashMap<String, Pattern> patternCache = new ConcurrentHashMap<String, Pattern>();

        public LegacyRulesContext(final Map rules) {
            this.rules = rules;
        }

        private boolean regexMatches(final String regex, final String value) {
            if (!patternCache.containsKey(regex)) {
                patternCache.putIfAbsent(regex, Pattern.compile(regex));
            }
            final Pattern pattern = patternCache.get(regex);
            final Matcher matcher = pattern.matcher(value);
            return matcher.matches();
        }

        public ContextDecision includes(final Map<String, String> resourceMap, final String action) {
            final String resource = createLegacyJobResourcePath(resourceMap);
            final List<ContextEvaluation> evaluations = new ArrayList<ContextEvaluation>();
            final Set<Map.Entry> entries = rules.entrySet();
            for (final Map.Entry entry : entries) {
                final Object ruleKey = entry.getKey();
                if (!(ruleKey instanceof String)) {
                    evaluations.add(new ContextEvaluation(Explanation.Code.REJECTED_CONTEXT_EVALUATION_ERROR,
                        "Invalid key type: " + ruleKey.getClass().getName()));
                    continue;
                }

                final String rule = (String) ruleKey;
                if (rule == null || rule.length() == 0) {
                    evaluations.add(new ContextEvaluation(Explanation.Code.REJECTED_CONTEXT_EVALUATION_ERROR,
                        "Resource is empty or null"));
                }

                if (regexMatches(rule, resource)) {
                    final Map ruleMap = (Map) entry.getValue();
                    final Object actionsKey = ruleMap.get(ACTIONS_SECTION);
                    if (actionsKey == null) {
                        evaluations.add(new ContextEvaluation(Explanation.Code.REJECTED_ACTIONS_DECLARED_EMPTY,
                            "No actions configured"));
                        continue;
                    }

                    if (actionsKey instanceof String) {
                        final String actions = (String) actionsKey;
                        if ("*".equals(actions) || actions.contains(action)) {
                            evaluations.add(new ContextEvaluation(Explanation.Code.GRANTED_ACTIONS_AND_COMMANDS_MATCHED,
                                "Legacy rule: " + rule + " action: " + actions));
                            return new ContextDecision(Explanation.Code.GRANTED_ACTIONS_AND_COMMANDS_MATCHED, true,
                                evaluations);
                        }
                    } else if (actionsKey instanceof List) {
                        final List actions = (List) actionsKey;
                        if (actions.contains(action)) {
                            evaluations.add(new ContextEvaluation(Explanation.Code.GRANTED_ACTIONS_AND_COMMANDS_MATCHED,
                                 "Legacy rule: " + rule + " action: " + actions));
                            return new ContextDecision(Explanation.Code.GRANTED_ACTIONS_AND_COMMANDS_MATCHED, true,
                                evaluations);
                        }
                    } else {
                        evaluations.add(new ContextEvaluation(Explanation.Code.REJECTED_CONTEXT_EVALUATION_ERROR,
                            "Invalid action type."));

                    }

                    evaluations.add(new ContextEvaluation(Explanation.Code.REJECTED_NO_ACTIONS_MATCHED,
                        "No actions matched"));
                }
            }
            return new ContextDecision(Explanation.Code.REJECTED, false, evaluations);
        }
    }

    /**
     * Returns decision for a resource and action, based on the "type" of the resource, and the rules defined in the
     * for: type: section of the policy def.
     */
    static class YamlAclContext implements AclContext {
        private String description = "Not Evaluated: " ;
        Map policyDef;
        private final ConcurrentHashMap<String, AclContext> typeContexts = new ConcurrentHashMap<String, AclContext>();
        TypeContextFactory typeContextFactory;
        LegacyContextFactory legacyContextFactory;

        YamlAclContext(final Map policyDef, final TypeContextFactory typeContextFactory,
                       final LegacyContextFactory legacyContextFactory) {
            this.policyDef = policyDef;
            this.typeContextFactory = typeContextFactory;
            this.legacyContextFactory = legacyContextFactory;
        }

        public String toString() {
            return "Context: " + description;
        }

        private AclContext createTypeContext(final List typeSection) {
            return typeContextFactory.createAclContext(typeSection);
        }

        private AclContext createLegacyContext(final Map rules) {
            return legacyContextFactory.createLegacyContext(rules);

        }

        @SuppressWarnings ("rawtypes")
        public ContextDecision includes(final Map<String, String> resourceMap, final String action) {
            final List<ContextEvaluation> evaluations = new ArrayList<ContextEvaluation>();

            //require description
            final Object descriptionValue = policyDef.get("description");
            if (descriptionValue == null || !(descriptionValue instanceof String)) {
                evaluations.add(new ContextEvaluation(Explanation.Code.REJECTED_NO_DESCRIPTION_PROVIDED,
                    "Policy is missing a description."));
                return new ContextDecision(Explanation.Code.REJECTED_NO_DESCRIPTION_PROVIDED, false, evaluations);
            }
            description = (String) descriptionValue;

            //require the resource to have a "type" value
            final String type = resourceMap.get(TYPE_PROPERTY);
            if (null == type) {
                evaluations.add(new ContextEvaluation(Explanation.Code.REJECTED_NO_RESOURCE_TYPE,
                    "Resource has no '" + TYPE_PROPERTY + "'."));
                return new ContextDecision(Explanation.Code.REJECTED_NO_RESOURCE_TYPE, false, evaluations);
            }

            final Object forMap = policyDef.get(FOR_SECTION);

            //require for section is a map
            if (null != forMap && !(forMap instanceof Map)) {
                evaluations.add(new ContextEvaluation(Explanation.Code.REJECTED_INVALID_FOR_SECTION,
                    "'" + FOR_SECTION + "' was not declared"));
                return new ContextDecision(Explanation.Code.REJECTED_INVALID_FOR_SECTION, false, evaluations);
            }

            final Map forsection = (Map) forMap;
            final Object typeSection = null != forsection ? forsection.get(type) : null;

            /**
             * true indicates the old style "rules:" section is in effect for a resource of type "job"
             */
            final boolean useLegacyRules = JOB_TYPE.equals(type) && policyDef.containsKey(RULES_SECTION) && policyDef
                .get(RULES_SECTION) instanceof Map;

            if (null == typeContexts.get(type)) {
                if (null != typeSection) {
                    typeContexts.putIfAbsent(type, createTypeContext((List) typeSection));
                } else if (useLegacyRules) {
                    final Object rulesValue = policyDef.get(RULES_SECTION);
                    final Map rules = (Map) rulesValue;
                    typeContexts.putIfAbsent(type, createLegacyContext(rules));
                } else {
                    evaluations.add(new ContextEvaluation(Explanation.Code.REJECTED_NO_RULES_DECLARED,
                        "Section for type '" + type + "' was not declared in " + FOR_SECTION + " section"));
                    return new ContextDecision(Explanation.Code.REJECTED_NO_RULES_DECLARED, false, evaluations);
                }
            }

            //evaluate resource given the type specific matcher

            final AclContext typeContext = typeContexts.get(type);
            return typeContext.includes(resourceMap, action);

        }


    }
}
