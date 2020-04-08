/*
 * SonarQube
 * Copyright (C) 2009-2020 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.issue.index;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.indices.TermsLookup;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.BucketOrder;
import org.elasticsearch.search.aggregations.HasAggregations;
import org.elasticsearch.search.aggregations.bucket.filter.FilterAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.filter.InternalFilter;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.search.aggregations.bucket.histogram.ExtendedBounds;
import org.elasticsearch.search.aggregations.bucket.terms.IncludeExclude;
import org.elasticsearch.search.aggregations.bucket.terms.InternalTerms;
import org.elasticsearch.search.aggregations.bucket.terms.StringTerms;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.max.InternalMax;
import org.elasticsearch.search.aggregations.metrics.min.Min;
import org.elasticsearch.search.aggregations.metrics.sum.SumAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.valuecount.InternalValueCount;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.joda.time.DateTimeZone;
import org.joda.time.Duration;
import org.sonar.api.issue.Issue;
import org.sonar.api.rule.Severity;
import org.sonar.api.rules.RuleType;
import org.sonar.api.utils.DateUtils;
import org.sonar.api.utils.System2;
import org.sonar.core.util.stream.MoreCollectors;
import org.sonar.db.rule.RuleDefinitionDto;
import org.sonar.server.es.BaseDoc;
import org.sonar.server.es.EsClient;
import org.sonar.server.es.EsUtils;
import org.sonar.server.es.IndexType;
import org.sonar.server.es.SearchOptions;
import org.sonar.server.es.Sorting;
import org.sonar.server.es.searchrequest.RequestFiltersComputer;
import org.sonar.server.es.searchrequest.RequestFiltersComputer.AllFilters;
import org.sonar.server.es.searchrequest.SimpleFieldTopAggregationDefinition;
import org.sonar.server.es.searchrequest.SubAggregationHelper;
import org.sonar.server.es.searchrequest.TopAggregationDefinition;
import org.sonar.server.es.searchrequest.TopAggregationDefinition.SimpleFieldFilterScope;
import org.sonar.server.es.searchrequest.TopAggregationHelper;
import org.sonar.server.issue.index.IssueQuery.PeriodStart;
import org.sonar.server.permission.index.AuthorizationDoc;
import org.sonar.server.permission.index.WebAuthorizationTypeSupport;
import org.sonar.server.security.SecurityStandards;
import org.sonar.server.user.UserSession;
import org.sonar.server.view.index.ViewIndexDefinition;

import static com.google.common.base.Preconditions.checkState;
import static java.lang.String.format;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.existsQuery;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.index.query.QueryBuilders.rangeQuery;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;
import static org.elasticsearch.index.query.QueryBuilders.termsQuery;
import static org.sonar.api.rules.RuleType.SECURITY_HOTSPOT;
import static org.sonar.api.rules.RuleType.VULNERABILITY;
import static org.sonar.core.util.stream.MoreCollectors.uniqueIndex;
import static org.sonar.server.es.BaseDoc.epochMillisToEpochSeconds;
import static org.sonar.server.es.EsUtils.escapeSpecialRegexChars;
import static org.sonar.server.es.IndexType.FIELD_INDEX_TYPE;
import static org.sonar.server.es.searchrequest.TopAggregationDefinition.NON_STICKY;
import static org.sonar.server.es.searchrequest.TopAggregationDefinition.STICKY;
import static org.sonar.server.es.searchrequest.TopAggregationHelper.NO_EXTRA_FILTER;
import static org.sonar.server.es.searchrequest.TopAggregationHelper.NO_OTHER_SUBAGGREGATION;
import static org.sonar.server.issue.index.IssueIndex.Facet.ASSIGNED_TO_ME;
import static org.sonar.server.issue.index.IssueIndex.Facet.ASSIGNEES;
import static org.sonar.server.issue.index.IssueIndex.Facet.AUTHOR;
import static org.sonar.server.issue.index.IssueIndex.Facet.AUTHORS;
import static org.sonar.server.issue.index.IssueIndex.Facet.CREATED_AT;
import static org.sonar.server.issue.index.IssueIndex.Facet.CWE;
import static org.sonar.server.issue.index.IssueIndex.Facet.DIRECTORIES;
import static org.sonar.server.issue.index.IssueIndex.Facet.FILE_UUIDS;
import static org.sonar.server.issue.index.IssueIndex.Facet.LANGUAGES;
import static org.sonar.server.issue.index.IssueIndex.Facet.MODULE_UUIDS;
import static org.sonar.server.issue.index.IssueIndex.Facet.OWASP_TOP_10;
import static org.sonar.server.issue.index.IssueIndex.Facet.PROJECT_UUIDS;
import static org.sonar.server.issue.index.IssueIndex.Facet.RESOLUTIONS;
import static org.sonar.server.issue.index.IssueIndex.Facet.RULES;
import static org.sonar.server.issue.index.IssueIndex.Facet.SANS_TOP_25;
import static org.sonar.server.issue.index.IssueIndex.Facet.SEVERITIES;
import static org.sonar.server.issue.index.IssueIndex.Facet.SONARSOURCE_SECURITY;
import static org.sonar.server.issue.index.IssueIndex.Facet.STATUSES;
import static org.sonar.server.issue.index.IssueIndex.Facet.TAGS;
import static org.sonar.server.issue.index.IssueIndex.Facet.TYPES;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_ASSIGNEE_UUID;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_AUTHOR_LOGIN;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_BRANCH_UUID;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_COMPONENT_UUID;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_CWE;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_DIRECTORY_PATH;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_EFFORT;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_FILE_PATH;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_FUNC_CLOSED_AT;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_FUNC_CREATED_AT;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_FUNC_UPDATED_AT;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_IS_MAIN_BRANCH;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_KEY;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_LANGUAGE;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_LINE;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_MODULE_PATH;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_MODULE_UUID;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_ORGANIZATION_UUID;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_OWASP_TOP_10;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_PROJECT_UUID;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_RESOLUTION;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_RULE_ID;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_SANS_TOP_25;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_SEVERITY;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_SEVERITY_VALUE;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_SQ_SECURITY_CATEGORY;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_STATUS;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_TAGS;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_TYPE;
import static org.sonar.server.issue.index.IssueIndexDefinition.FIELD_ISSUE_VULNERABILITY_PROBABILITY;
import static org.sonar.server.issue.index.IssueIndexDefinition.TYPE_ISSUE;
import static org.sonar.server.security.SecurityStandards.SANS_TOP_25_INSECURE_INTERACTION;
import static org.sonar.server.security.SecurityStandards.SANS_TOP_25_POROUS_DEFENSES;
import static org.sonar.server.security.SecurityStandards.SANS_TOP_25_RISKY_RESOURCE;
import static org.sonar.server.view.index.ViewIndexDefinition.TYPE_VIEW;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.DEPRECATED_PARAM_AUTHORS;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.FACET_MODE_EFFORT;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_ASSIGNEES;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_AUTHOR;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_CREATED_AT;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_CWE;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_DIRECTORIES;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_FILE_UUIDS;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_LANGUAGES;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_MODULE_UUIDS;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_OWASP_TOP_10;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_RESOLUTIONS;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_RULES;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_SANS_TOP_25;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_SEVERITIES;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_SONARSOURCE_SECURITY;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_STATUSES;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_TAGS;
import static org.sonarqube.ws.client.issue.IssuesWsParameters.PARAM_TYPES;

/**
 * The unique entry-point to interact with Elasticsearch index "issues".
 * All the requests are listed here.
 */
public class IssueIndex {

  public static final String FACET_PROJECTS = "projects";
  public static final String FACET_ASSIGNED_TO_ME = "assigned_to_me";

  private static final int DEFAULT_FACET_SIZE = 15;
  private static final int MAX_FACET_SIZE = 100;
  private static final String AGG_VULNERABILITIES = "vulnerabilities";
  private static final String AGG_SEVERITIES = "severities";
  private static final String AGG_TO_REVIEW_SECURITY_HOTSPOTS = "toReviewSecurityHotspots";
  private static final String AGG_IN_REVIEW_SECURITY_HOTSPOTS = "inReviewSecurityHotspots";
  private static final String AGG_REVIEWED_SECURITY_HOTSPOTS = "reviewedSecurityHotspots";
  private static final String AGG_CWES = "cwes";
  private static final BoolQueryBuilder NON_RESOLVED_VULNERABILITIES_FILTER = boolQuery()
    .filter(termQuery(FIELD_ISSUE_TYPE, VULNERABILITY.name()))
    .mustNot(existsQuery(FIELD_ISSUE_RESOLUTION));
  private static final BoolQueryBuilder IN_REVIEW_HOTSPOTS_FILTER = boolQuery()
    .filter(termQuery(FIELD_ISSUE_TYPE, SECURITY_HOTSPOT.name()))
    .filter(termQuery(FIELD_ISSUE_STATUS, Issue.STATUS_IN_REVIEW))
    .mustNot(existsQuery(FIELD_ISSUE_RESOLUTION));
  private static final BoolQueryBuilder TO_REVIEW_HOTSPOTS_FILTER = boolQuery()
    .filter(termQuery(FIELD_ISSUE_TYPE, SECURITY_HOTSPOT.name()))
    .filter(termQuery(FIELD_ISSUE_STATUS, Issue.STATUS_TO_REVIEW))
    .mustNot(existsQuery(FIELD_ISSUE_RESOLUTION));
  private static final BoolQueryBuilder REVIEWED_HOTSPOTS_FILTER = boolQuery()
    .filter(termQuery(FIELD_ISSUE_TYPE, SECURITY_HOTSPOT.name()))
    .filter(termQuery(FIELD_ISSUE_STATUS, Issue.STATUS_REVIEWED))
    .filter(termQuery(FIELD_ISSUE_RESOLUTION, Issue.RESOLUTION_FIXED));

  private static final Object[] NO_SELECTED_VALUES = {0};
  private static final SimpleFieldTopAggregationDefinition EFFORT_TOP_AGGREGATION = new SimpleFieldTopAggregationDefinition(FIELD_ISSUE_EFFORT, NON_STICKY);

  public enum Facet {
    SEVERITIES(PARAM_SEVERITIES, FIELD_ISSUE_SEVERITY, STICKY, Severity.ALL.size()),
    STATUSES(PARAM_STATUSES, FIELD_ISSUE_STATUS, STICKY, Issue.STATUSES.size()),
    // Resolutions facet returns one more element than the number of resolutions to take into account unresolved issues
    RESOLUTIONS(PARAM_RESOLUTIONS, FIELD_ISSUE_RESOLUTION, STICKY, Issue.RESOLUTIONS.size() + 1),
    TYPES(PARAM_TYPES, FIELD_ISSUE_TYPE, STICKY, RuleType.values().length),
    LANGUAGES(PARAM_LANGUAGES, FIELD_ISSUE_LANGUAGE, STICKY, MAX_FACET_SIZE),
    RULES(PARAM_RULES, FIELD_ISSUE_RULE_ID, STICKY, MAX_FACET_SIZE),
    TAGS(PARAM_TAGS, FIELD_ISSUE_TAGS, STICKY, MAX_FACET_SIZE),
    AUTHORS(DEPRECATED_PARAM_AUTHORS, FIELD_ISSUE_AUTHOR_LOGIN, STICKY, MAX_FACET_SIZE),
    AUTHOR(PARAM_AUTHOR, FIELD_ISSUE_AUTHOR_LOGIN, STICKY, MAX_FACET_SIZE),
    PROJECT_UUIDS(FACET_PROJECTS, FIELD_ISSUE_PROJECT_UUID, STICKY, MAX_FACET_SIZE),
    MODULE_UUIDS(PARAM_MODULE_UUIDS, FIELD_ISSUE_MODULE_UUID, STICKY, MAX_FACET_SIZE),
    FILE_UUIDS(PARAM_FILE_UUIDS, FIELD_ISSUE_COMPONENT_UUID, STICKY, MAX_FACET_SIZE),
    DIRECTORIES(PARAM_DIRECTORIES, FIELD_ISSUE_DIRECTORY_PATH, STICKY, MAX_FACET_SIZE),
    ASSIGNEES(PARAM_ASSIGNEES, FIELD_ISSUE_ASSIGNEE_UUID, STICKY, MAX_FACET_SIZE),
    ASSIGNED_TO_ME(FACET_ASSIGNED_TO_ME, FIELD_ISSUE_ASSIGNEE_UUID, STICKY, 1),
    OWASP_TOP_10(PARAM_OWASP_TOP_10, FIELD_ISSUE_OWASP_TOP_10, STICKY, DEFAULT_FACET_SIZE),
    SANS_TOP_25(PARAM_SANS_TOP_25, FIELD_ISSUE_SANS_TOP_25, STICKY, DEFAULT_FACET_SIZE),
    CWE(PARAM_CWE, FIELD_ISSUE_CWE, STICKY, DEFAULT_FACET_SIZE),
    CREATED_AT(PARAM_CREATED_AT, FIELD_ISSUE_FUNC_CREATED_AT, NON_STICKY),
    SONARSOURCE_SECURITY(PARAM_SONARSOURCE_SECURITY, FIELD_ISSUE_SQ_SECURITY_CATEGORY, STICKY, DEFAULT_FACET_SIZE);

    private final String name;
    private final SimpleFieldTopAggregationDefinition topAggregation;
    private final Integer numberOfTerms;

    Facet(String name, String fieldName, boolean sticky, int numberOfTerms) {
      this.name = name;
      this.topAggregation = new SimpleFieldTopAggregationDefinition(fieldName, sticky);
      this.numberOfTerms = numberOfTerms;
    }

    Facet(String name, String fieldName, boolean sticky) {
      this.name = name;
      this.topAggregation = new SimpleFieldTopAggregationDefinition(fieldName, sticky);
      this.numberOfTerms = null;
    }

    public String getName() {
      return name;
    }

    public String getFieldName() {
      return topAggregation.getFilterScope().getFieldName();
    }

    public TopAggregationDefinition.FilterScope getFilterScope() {
      return topAggregation.getFilterScope();
    }

    public SimpleFieldTopAggregationDefinition getTopAggregationDef() {
      return topAggregation;
    }

    public int getNumberOfTerms() {
      checkState(numberOfTerms != null, "numberOfTerms should have been provided in constructor");

      return numberOfTerms;
    }
  }

  private static final Map<String, Facet> FACETS_BY_NAME = Arrays.stream(Facet.values())
    .collect(uniqueIndex(Facet::getName));

  private static final String SUBSTRING_MATCH_REGEXP = ".*%s.*";
  // TODO to be documented
  // TODO move to Facets ?
  private static final String FACET_SUFFIX_MISSING = "_missing";
  private static final String IS_ASSIGNED_FILTER = "__isAssigned";
  private static final SumAggregationBuilder EFFORT_AGGREGATION = AggregationBuilders.sum(FACET_MODE_EFFORT).field(FIELD_ISSUE_EFFORT);
  private static final BucketOrder EFFORT_AGGREGATION_ORDER = BucketOrder.aggregation(FACET_MODE_EFFORT, false);
  private static final Duration TWENTY_DAYS = Duration.standardDays(20L);
  private static final Duration TWENTY_WEEKS = Duration.standardDays(20L * 7L);
  private static final Duration TWENTY_MONTHS = Duration.standardDays(20L * 30L);
  private static final String AGG_COUNT = "count";
  private final Sorting sorting;
  private final EsClient client;
  private final System2 system;
  private final UserSession userSession;
  private final WebAuthorizationTypeSupport authorizationTypeSupport;

  public IssueIndex(EsClient client, System2 system, UserSession userSession, WebAuthorizationTypeSupport authorizationTypeSupport) {
    this.client = client;
    this.system = system;
    this.userSession = userSession;
    this.authorizationTypeSupport = authorizationTypeSupport;

    this.sorting = new Sorting();
    this.sorting.add(IssueQuery.SORT_BY_STATUS, FIELD_ISSUE_STATUS);
    this.sorting.add(IssueQuery.SORT_BY_SEVERITY, FIELD_ISSUE_SEVERITY_VALUE);
    this.sorting.add(IssueQuery.SORT_BY_CREATION_DATE, FIELD_ISSUE_FUNC_CREATED_AT);
    this.sorting.add(IssueQuery.SORT_BY_UPDATE_DATE, FIELD_ISSUE_FUNC_UPDATED_AT);
    this.sorting.add(IssueQuery.SORT_BY_CLOSE_DATE, FIELD_ISSUE_FUNC_CLOSED_AT);
    this.sorting.add(IssueQuery.SORT_BY_FILE_LINE, FIELD_ISSUE_PROJECT_UUID);
    this.sorting.add(IssueQuery.SORT_BY_FILE_LINE, FIELD_ISSUE_FILE_PATH);
    this.sorting.add(IssueQuery.SORT_BY_FILE_LINE, FIELD_ISSUE_LINE);
    this.sorting.add(IssueQuery.SORT_BY_FILE_LINE, FIELD_ISSUE_SEVERITY_VALUE).reverse();
    this.sorting.add(IssueQuery.SORT_BY_FILE_LINE, FIELD_ISSUE_KEY);
    this.sorting.add(IssueQuery.SORT_HOTSPOTS, FIELD_ISSUE_VULNERABILITY_PROBABILITY).reverse();
    this.sorting.add(IssueQuery.SORT_HOTSPOTS, FIELD_ISSUE_SQ_SECURITY_CATEGORY);
    this.sorting.add(IssueQuery.SORT_HOTSPOTS, FIELD_ISSUE_RULE_ID);
    this.sorting.add(IssueQuery.SORT_HOTSPOTS, FIELD_ISSUE_PROJECT_UUID);
    this.sorting.add(IssueQuery.SORT_HOTSPOTS, FIELD_ISSUE_FILE_PATH);
    this.sorting.add(IssueQuery.SORT_HOTSPOTS, FIELD_ISSUE_LINE);
    this.sorting.add(IssueQuery.SORT_HOTSPOTS, FIELD_ISSUE_KEY);

    // by default order by created date, project, file, line and issue key (in order to be deterministic when same ms)
    this.sorting.addDefault(FIELD_ISSUE_FUNC_CREATED_AT).reverse();
    this.sorting.addDefault(FIELD_ISSUE_PROJECT_UUID);
    this.sorting.addDefault(FIELD_ISSUE_FILE_PATH);
    this.sorting.addDefault(FIELD_ISSUE_LINE);
    this.sorting.addDefault(FIELD_ISSUE_KEY);
  }

  public SearchResponse search(IssueQuery query, SearchOptions options) {
    SearchRequestBuilder esRequest = client.prepareSearch(TYPE_ISSUE.getMainType());

    configureSorting(query, esRequest);
    configurePagination(options, esRequest);
    configureRouting(query, options, esRequest);

    AllFilters allFilters = createAllFilters(query);
    RequestFiltersComputer filterComputer = newFilterComputer(options, allFilters);

    configureTopAggregations(query, options, esRequest, allFilters, filterComputer);
    configureQuery(esRequest, filterComputer);
    configureTopFilters(esRequest, filterComputer);

    esRequest.setFetchSource(false);

    return esRequest.get();
  }

  private void configureTopAggregations(IssueQuery query, SearchOptions options, SearchRequestBuilder esRequest, AllFilters allFilters, RequestFiltersComputer filterComputer) {
    TopAggregationHelper aggregationHelper = newAggregationHelper(filterComputer, query);

    configureTopAggregations(aggregationHelper, query, options, allFilters, esRequest);
  }

  private static void configureQuery(SearchRequestBuilder esRequest, RequestFiltersComputer filterComputer) {
    QueryBuilder esQuery = filterComputer.getQueryFilters()
      .map(t -> (QueryBuilder) boolQuery().must(matchAllQuery()).filter(t))
      .orElse(matchAllQuery());
    esRequest.setQuery(esQuery);
  }

  private static void configureTopFilters(SearchRequestBuilder esRequest, RequestFiltersComputer filterComputer) {
    filterComputer.getPostFilters().ifPresent(esRequest::setPostFilter);
  }

  /**
   * Optimization - do not send ES request to all shards when scope is restricted
   * to a set of projects. Because project UUID is used for routing, the request
   * can be sent to only the shards containing the specified projects.
   * Note that sticky facets may involve all projects, so this optimization must be
   * disabled when facets are enabled.
   */
  private static void configureRouting(IssueQuery query, SearchOptions options, SearchRequestBuilder requestBuilder) {
    Collection<String> uuids = query.projectUuids();
    if (!uuids.isEmpty() && options.getFacets().isEmpty()) {
      requestBuilder.setRouting(uuids.stream().map(AuthorizationDoc::idOf).toArray(String[]::new));
    }
  }

  private static void configurePagination(SearchOptions options, SearchRequestBuilder esSearch) {
    esSearch.setFrom(options.getOffset()).setSize(options.getLimit());
  }

  private AllFilters createAllFilters(IssueQuery query) {
    AllFilters filters = RequestFiltersComputer.newAllFilters();
    filters.addFilter("__indexType", new SimpleFieldFilterScope(FIELD_INDEX_TYPE), termQuery(FIELD_INDEX_TYPE, TYPE_ISSUE.getName()));
    filters.addFilter("__authorization", new SimpleFieldFilterScope("parent"), createAuthorizationFilter());

    // Issue is assigned Filter
    if (BooleanUtils.isTrue(query.assigned())) {
      filters.addFilter(IS_ASSIGNED_FILTER, Facet.ASSIGNEES.getFilterScope(), existsQuery(FIELD_ISSUE_ASSIGNEE_UUID));
    } else if (BooleanUtils.isFalse(query.assigned())) {
      filters.addFilter(IS_ASSIGNED_FILTER, ASSIGNEES.getFilterScope(), boolQuery().mustNot(existsQuery(FIELD_ISSUE_ASSIGNEE_UUID)));
    }

    // Issue is Resolved Filter
    if (BooleanUtils.isTrue(query.resolved())) {
      filters.addFilter("__isResolved", Facet.RESOLUTIONS.getFilterScope(), existsQuery(FIELD_ISSUE_RESOLUTION));
    } else if (BooleanUtils.isFalse(query.resolved())) {
      filters.addFilter("__isResolved", Facet.RESOLUTIONS.getFilterScope(), boolQuery().mustNot(existsQuery(FIELD_ISSUE_RESOLUTION)));
    }

    // Field Filters
    filters.addFilter(FIELD_ISSUE_KEY, new SimpleFieldFilterScope(FIELD_ISSUE_KEY), createTermsFilter(FIELD_ISSUE_KEY, query.issueKeys()));
    filters.addFilter(FIELD_ISSUE_ASSIGNEE_UUID, ASSIGNEES.getFilterScope(), createTermsFilter(FIELD_ISSUE_ASSIGNEE_UUID, query.assignees()));
    filters.addFilter(FIELD_ISSUE_LANGUAGE, LANGUAGES.getFilterScope(), createTermsFilter(FIELD_ISSUE_LANGUAGE, query.languages()));
    filters.addFilter(FIELD_ISSUE_TAGS, TAGS.getFilterScope(), createTermsFilter(FIELD_ISSUE_TAGS, query.tags()));
    filters.addFilter(FIELD_ISSUE_TYPE, TYPES.getFilterScope(), createTermsFilter(FIELD_ISSUE_TYPE, query.types()));
    filters.addFilter(
      FIELD_ISSUE_RESOLUTION, RESOLUTIONS.getFilterScope(),
      createTermsFilter(FIELD_ISSUE_RESOLUTION, query.resolutions()));
    filters.addFilter(
      FIELD_ISSUE_AUTHOR_LOGIN, AUTHOR.getFilterScope(),
      createTermsFilter(FIELD_ISSUE_AUTHOR_LOGIN, query.authors()));
    filters.addFilter(
      FIELD_ISSUE_RULE_ID, RULES.getFilterScope(), createTermsFilter(
        FIELD_ISSUE_RULE_ID,
        query.rules().stream().map(IssueDoc::formatRuleId).collect(toList())));
    filters.addFilter(FIELD_ISSUE_STATUS, STATUSES.getFilterScope(), createTermsFilter(FIELD_ISSUE_STATUS, query.statuses()));
    filters.addFilter(
      FIELD_ISSUE_ORGANIZATION_UUID, new SimpleFieldFilterScope(FIELD_ISSUE_ORGANIZATION_UUID),
      createTermFilter(FIELD_ISSUE_ORGANIZATION_UUID, query.organizationUuid()));
    filters.addFilter(
      FIELD_ISSUE_OWASP_TOP_10, OWASP_TOP_10.getFilterScope(),
      createTermsFilter(FIELD_ISSUE_OWASP_TOP_10, query.owaspTop10()));
    filters.addFilter(
      FIELD_ISSUE_SANS_TOP_25, SANS_TOP_25.getFilterScope(),
      createTermsFilter(FIELD_ISSUE_SANS_TOP_25, query.sansTop25()));
    filters.addFilter(FIELD_ISSUE_CWE, CWE.getFilterScope(), createTermsFilter(FIELD_ISSUE_CWE, query.cwe()));
    addSeverityFilter(query, filters);
    filters.addFilter(
      FIELD_ISSUE_SQ_SECURITY_CATEGORY, SONARSOURCE_SECURITY.getFilterScope(),
      createTermsFilter(FIELD_ISSUE_SQ_SECURITY_CATEGORY, query.sonarsourceSecurity()));

    addComponentRelatedFilters(query, filters);
    addDatesFilter(filters, query);
    addCreatedAfterByProjectsFilter(filters, query);
    return filters;
  }

  private static void addSeverityFilter(IssueQuery query, AllFilters allFilters) {
    QueryBuilder severityFieldFilter = createTermsFilter(FIELD_ISSUE_SEVERITY, query.severities());
    if (severityFieldFilter != null) {
      allFilters.addFilter(
        FIELD_ISSUE_SEVERITY,
        SEVERITIES.getFilterScope(),
        boolQuery()
          .must(severityFieldFilter)
          // Ignore severity of Security HotSpots
          .mustNot(termQuery(FIELD_ISSUE_TYPE, SECURITY_HOTSPOT.name())));
    }
  }

  private static void addComponentRelatedFilters(IssueQuery query, AllFilters filters) {
    addCommonComponentRelatedFilters(query, filters);
    if (query.viewUuids().isEmpty()) {
      addBranchComponentRelatedFilters(query, filters);
    } else {
      addViewRelatedFilters(query, filters);
    }
  }

  private static void addCommonComponentRelatedFilters(IssueQuery query, AllFilters filters) {
    QueryBuilder componentFilter = createTermsFilter(FIELD_ISSUE_COMPONENT_UUID, query.componentUuids());
    QueryBuilder fileFilter = createTermsFilter(FIELD_ISSUE_COMPONENT_UUID, query.fileUuids());

    if (BooleanUtils.isTrue(query.onComponentOnly())) {
      filters.addFilter(FIELD_ISSUE_COMPONENT_UUID, new SimpleFieldFilterScope(FIELD_ISSUE_COMPONENT_UUID), componentFilter);
    } else {
      filters.addFilter(
        FIELD_ISSUE_PROJECT_UUID, new SimpleFieldFilterScope(FIELD_ISSUE_PROJECT_UUID),
        createTermsFilter(FIELD_ISSUE_PROJECT_UUID, query.projectUuids()));
      filters.addFilter(
        "__module", new SimpleFieldFilterScope(FIELD_ISSUE_MODULE_PATH),
        createTermsFilter(FIELD_ISSUE_MODULE_PATH, query.moduleRootUuids()));
      filters.addFilter(
        FIELD_ISSUE_MODULE_UUID, new SimpleFieldFilterScope(FIELD_ISSUE_MODULE_UUID),
        createTermsFilter(FIELD_ISSUE_MODULE_UUID, query.moduleUuids()));
      filters.addFilter(
        FIELD_ISSUE_DIRECTORY_PATH, new SimpleFieldFilterScope(FIELD_ISSUE_DIRECTORY_PATH),
        createTermsFilter(FIELD_ISSUE_DIRECTORY_PATH, query.directories()));
      filters.addFilter(
        FIELD_ISSUE_COMPONENT_UUID, new SimpleFieldFilterScope(FIELD_ISSUE_COMPONENT_UUID),
        fileFilter == null ? componentFilter : fileFilter);
    }
  }

  private static void addBranchComponentRelatedFilters(IssueQuery query, AllFilters allFilters) {
    if (BooleanUtils.isTrue(query.onComponentOnly())) {
      return;
    }
    allFilters.addFilter(
      "__is_main_branch", new SimpleFieldFilterScope(FIELD_ISSUE_IS_MAIN_BRANCH),
      createTermFilter(FIELD_ISSUE_IS_MAIN_BRANCH, Boolean.toString(query.isMainBranch())));
    allFilters.addFilter(
      FIELD_ISSUE_BRANCH_UUID, new SimpleFieldFilterScope(FIELD_ISSUE_BRANCH_UUID),
      createTermFilter(FIELD_ISSUE_BRANCH_UUID, query.branchUuid()));
  }

  private static void addViewRelatedFilters(IssueQuery query, AllFilters allFilters) {
    if (BooleanUtils.isTrue(query.onComponentOnly())) {
      return;
    }
    Collection<String> viewUuids = query.viewUuids();
    String branchUuid = query.branchUuid();
    boolean onApplicationBranch = branchUuid != null && !viewUuids.isEmpty();
    if (onApplicationBranch) {
      allFilters.addFilter("__view", new SimpleFieldFilterScope("view"), createViewFilter(singletonList(query.branchUuid())));
    } else {
      allFilters.addFilter("__is_main_branch", new SimpleFieldFilterScope(FIELD_ISSUE_IS_MAIN_BRANCH), createTermFilter(FIELD_ISSUE_IS_MAIN_BRANCH, Boolean.toString(true)));
      allFilters.addFilter("__view", new SimpleFieldFilterScope("view"), createViewFilter(viewUuids));
    }
  }

  @CheckForNull
  private static QueryBuilder createViewFilter(Collection<String> viewUuids) {
    if (viewUuids.isEmpty()) {
      return null;
    }

    BoolQueryBuilder viewsFilter = boolQuery();
    for (String viewUuid : viewUuids) {
      IndexType.IndexMainType mainType = TYPE_VIEW;
      viewsFilter.should(QueryBuilders.termsLookupQuery(FIELD_ISSUE_BRANCH_UUID,
        new TermsLookup(
          mainType.getIndex().getName(),
          mainType.getType(),
          viewUuid,
          ViewIndexDefinition.FIELD_PROJECTS)));
    }
    return viewsFilter;
  }

  private static RequestFiltersComputer newFilterComputer(SearchOptions options, AllFilters allFilters) {
    Collection<String> facetNames = options.getFacets();
    Set<TopAggregationDefinition<?>> facets = Stream.concat(
      Stream.of(EFFORT_TOP_AGGREGATION),
      facetNames.stream()
        .map(FACETS_BY_NAME::get)
        .filter(Objects::nonNull)
        .map(Facet::getTopAggregationDef))
      .collect(MoreCollectors.toSet(facetNames.size()));

    return new RequestFiltersComputer(allFilters, facets);
  }

  private static TopAggregationHelper newAggregationHelper(RequestFiltersComputer filterComputer, IssueQuery query) {
    if (hasQueryEffortFacet(query)) {
      return new TopAggregationHelper(filterComputer, new SubAggregationHelper(EFFORT_AGGREGATION, EFFORT_AGGREGATION_ORDER));
    }
    return new TopAggregationHelper(filterComputer, new SubAggregationHelper());
  }

  private static AggregationBuilder addEffortAggregationIfNeeded(IssueQuery query, AggregationBuilder aggregation) {
    if (hasQueryEffortFacet(query)) {
      aggregation.subAggregation(EFFORT_AGGREGATION);
    }
    return aggregation;
  }

  private static boolean hasQueryEffortFacet(IssueQuery query) {
    return FACET_MODE_EFFORT.equals(query.facetMode());
  }

  @CheckForNull
  private static QueryBuilder createTermsFilter(String field, Collection<?> values) {
    return values.isEmpty() ? null : termsQuery(field, values);
  }

  @CheckForNull
  private static QueryBuilder createTermFilter(String field, @Nullable String value) {
    return value == null ? null : termQuery(field, value);
  }

  private void configureSorting(IssueQuery query, SearchRequestBuilder esRequest) {
    createSortBuilders(query).forEach(esRequest::addSort);
  }

  private List<FieldSortBuilder> createSortBuilders(IssueQuery query) {
    String sortField = query.sort();
    if (sortField != null) {
      boolean asc = BooleanUtils.isTrue(query.asc());
      return sorting.fill(sortField, asc);
    }
    return sorting.fillDefault();
  }

  private QueryBuilder createAuthorizationFilter() {
    return authorizationTypeSupport.createQueryFilter();
  }

  private void addDatesFilter(AllFilters filters, IssueQuery query) {
    PeriodStart createdAfter = query.createdAfter();
    Date createdBefore = query.createdBefore();

    validateCreationDateBounds(createdBefore, createdAfter != null ? createdAfter.date() : null);

    if (createdAfter != null) {
      filters.addFilter(
        "__createdAfter", CREATED_AT.getFilterScope(),
        QueryBuilders
          .rangeQuery(FIELD_ISSUE_FUNC_CREATED_AT)
          .from(BaseDoc.dateToEpochSeconds(createdAfter.date()), createdAfter.inclusive()));
    }
    if (createdBefore != null) {
      filters.addFilter(
        "__createdBefore", CREATED_AT.getFilterScope(),
        QueryBuilders
          .rangeQuery(FIELD_ISSUE_FUNC_CREATED_AT)
          .lt(BaseDoc.dateToEpochSeconds(createdBefore)));
    }
    Date createdAt = query.createdAt();
    if (createdAt != null) {
      filters.addFilter(
        "__createdAt", CREATED_AT.getFilterScope(),
        termQuery(FIELD_ISSUE_FUNC_CREATED_AT, BaseDoc.dateToEpochSeconds(createdAt)));
    }
  }

  private static void addCreatedAfterByProjectsFilter(AllFilters allFilters, IssueQuery query) {
    Map<String, PeriodStart> createdAfterByProjectUuids = query.createdAfterByProjectUuids();
    BoolQueryBuilder boolQueryBuilder = boolQuery();
    createdAfterByProjectUuids.forEach((projectUuid, createdAfterDate) -> boolQueryBuilder.should(boolQuery()
      .filter(termQuery(FIELD_ISSUE_PROJECT_UUID, projectUuid))
      .filter(rangeQuery(FIELD_ISSUE_FUNC_CREATED_AT).from(BaseDoc.dateToEpochSeconds(createdAfterDate.date()), createdAfterDate.inclusive()))));
    allFilters.addFilter("createdAfterByProjectUuids", new SimpleFieldFilterScope("TODO::???"), boolQueryBuilder);
  }

  private void validateCreationDateBounds(@Nullable Date createdBefore, @Nullable Date createdAfter) {
    Preconditions.checkArgument(createdAfter == null || createdAfter.before(new Date(system.now())),
      "Start bound cannot be in the future");
    Preconditions.checkArgument(createdAfter == null || createdBefore == null || createdAfter.before(createdBefore),
      "Start bound cannot be larger or equal to end bound");
  }

  private void configureTopAggregations(TopAggregationHelper aggregationHelper, IssueQuery query, SearchOptions options,
    AllFilters queryFilters, SearchRequestBuilder esRequest) {
    addFacetIfNeeded(options, aggregationHelper, esRequest, STATUSES, NO_SELECTED_VALUES);
    addFacetIfNeeded(options, aggregationHelper, esRequest, PROJECT_UUIDS, query.projectUuids().toArray());
    addFacetIfNeeded(options, aggregationHelper, esRequest, MODULE_UUIDS, query.moduleUuids().toArray());
    addFacetIfNeeded(options, aggregationHelper, esRequest, DIRECTORIES, query.directories().toArray());
    addFacetIfNeeded(options, aggregationHelper, esRequest, FILE_UUIDS, query.fileUuids().toArray());
    addFacetIfNeeded(options, aggregationHelper, esRequest, LANGUAGES, query.languages().toArray());
    addFacetIfNeeded(options, aggregationHelper, esRequest, RULES, query.rules().stream().map(RuleDefinitionDto::getId).toArray());
    addFacetIfNeeded(options, aggregationHelper, esRequest, AUTHORS, query.authors().toArray());
    addFacetIfNeeded(options, aggregationHelper, esRequest, AUTHOR, query.authors().toArray());
    addFacetIfNeeded(options, aggregationHelper, esRequest, TAGS, query.tags().toArray());
    addFacetIfNeeded(options, aggregationHelper, esRequest, TYPES, query.types().toArray());
    addFacetIfNeeded(options, aggregationHelper, esRequest, OWASP_TOP_10, query.owaspTop10().toArray());
    addFacetIfNeeded(options, aggregationHelper, esRequest, SANS_TOP_25, query.sansTop25().toArray());
    addFacetIfNeeded(options, aggregationHelper, esRequest, CWE, query.cwe().toArray());
    addFacetIfNeeded(options, aggregationHelper, esRequest, SONARSOURCE_SECURITY, query.sonarsourceSecurity().toArray());
    addSeverityFacetIfNeeded(options, aggregationHelper, esRequest);
    addResolutionFacetIfNeeded(options, query, aggregationHelper, esRequest);
    addAssigneesFacetIfNeeded(options, query, aggregationHelper, esRequest);
    addCreatedAtFacetIfNeeded(options, query, aggregationHelper, queryFilters, esRequest);
    addAssignedToMeFacetIfNeeded(options, aggregationHelper, esRequest);
    addEffortTopAggregation(aggregationHelper, esRequest);
  }

  private static void addFacetIfNeeded(SearchOptions options, TopAggregationHelper aggregationHelper,
    SearchRequestBuilder esRequest, Facet facet, Object[] selectedValues) {
    if (!options.getFacets().contains(facet.getName())) {
      return;
    }

    FilterAggregationBuilder topAggregation = aggregationHelper.buildTermTopAggregation(
      facet.getName(), facet.getTopAggregationDef(), facet.getNumberOfTerms(),
      NO_EXTRA_FILTER,
      t -> aggregationHelper.getSubAggregationHelper().buildSelectedItemsAggregation(facet.getName(), facet.getTopAggregationDef(), selectedValues)
        .ifPresent(t::subAggregation));
    esRequest.addAggregation(topAggregation);
  }

  private static void addSeverityFacetIfNeeded(SearchOptions options, TopAggregationHelper aggregationHelper, SearchRequestBuilder esRequest) {
    if (!options.getFacets().contains(PARAM_SEVERITIES)) {
      return;
    }

    AggregationBuilder aggregation = aggregationHelper.buildTermTopAggregation(
      SEVERITIES.getName(), SEVERITIES.getTopAggregationDef(), SEVERITIES.getNumberOfTerms(),
      // Ignore severity of Security HotSpots
      filter -> filter.mustNot(termQuery(FIELD_ISSUE_TYPE, SECURITY_HOTSPOT.name())),
      NO_OTHER_SUBAGGREGATION);
    esRequest.addAggregation(aggregation);
  }

  private static void addResolutionFacetIfNeeded(SearchOptions options, IssueQuery query, TopAggregationHelper aggregationHelper, SearchRequestBuilder esRequest) {
    if (!options.getFacets().contains(PARAM_RESOLUTIONS)) {
      return;
    }

    AggregationBuilder aggregation = aggregationHelper.buildTermTopAggregation(
      RESOLUTIONS.getName(), RESOLUTIONS.getTopAggregationDef(), RESOLUTIONS.getNumberOfTerms(),
      NO_EXTRA_FILTER,
      t -> {
        // add aggregation of type "missing" to return count of unresolved issues in the facet
        t.subAggregation(
          addEffortAggregationIfNeeded(query, AggregationBuilders
            .missing(RESOLUTIONS.getName() + FACET_SUFFIX_MISSING)
            .field(RESOLUTIONS.getFieldName())));
      });
    esRequest.addAggregation(aggregation);
  }

  private static void addAssigneesFacetIfNeeded(SearchOptions options, IssueQuery query, TopAggregationHelper aggregationHelper, SearchRequestBuilder esRequest) {
    if (!options.getFacets().contains(PARAM_ASSIGNEES)) {
      return;
    }

    Consumer<FilterAggregationBuilder> assigneeAggregations = t -> {
      // optional second aggregation to return the issue count for selected assignees (if any)
      Object[] assignees = query.assignees().toArray();
      aggregationHelper.getSubAggregationHelper().buildSelectedItemsAggregation(ASSIGNEES.getName(), ASSIGNEES.getTopAggregationDef(), assignees)
        .ifPresent(t::subAggregation);

      // third aggregation to always return the count of unassigned in the assignee facet
      t.subAggregation(addEffortAggregationIfNeeded(query, AggregationBuilders
        .missing(ASSIGNEES.getName() + FACET_SUFFIX_MISSING)
        .field(ASSIGNEES.getFieldName())));
    };

    AggregationBuilder aggregation = aggregationHelper.buildTermTopAggregation(
      ASSIGNEES.getName(), ASSIGNEES.getTopAggregationDef(), ASSIGNEES.getNumberOfTerms(),
      NO_EXTRA_FILTER, assigneeAggregations);
    esRequest.addAggregation(aggregation);
  }

  private void addCreatedAtFacetIfNeeded(SearchOptions options, IssueQuery query, TopAggregationHelper aggregationHelper, AllFilters allFilters,
    SearchRequestBuilder esRequest) {
    if (options.getFacets().contains(PARAM_CREATED_AT)) {
      getCreatedAtFacet(query, aggregationHelper, allFilters).ifPresent(esRequest::addAggregation);
    }
  }

  private Optional<AggregationBuilder> getCreatedAtFacet(IssueQuery query, TopAggregationHelper aggregationHelper, AllFilters allFilters) {
    long startTime;
    boolean startInclusive;
    PeriodStart createdAfter = query.createdAfter();
    if (createdAfter == null) {
      OptionalLong minDate = getMinCreatedAt(allFilters);
      if (!minDate.isPresent()) {
        return Optional.empty();
      }
      startTime = minDate.getAsLong();
      startInclusive = true;
    } else {
      startTime = createdAfter.date().getTime();
      startInclusive = createdAfter.inclusive();
    }
    Date createdBefore = query.createdBefore();
    long endTime = createdBefore == null ? system.now() : createdBefore.getTime();

    Duration timeSpan = new Duration(startTime, endTime);
    DateHistogramInterval bucketSize = computeDateHistogramBucketSize(timeSpan);

    FilterAggregationBuilder topAggregation = aggregationHelper.buildTopAggregation(
      CREATED_AT.getName(),
      CREATED_AT.getTopAggregationDef(),
      NO_EXTRA_FILTER,
      t -> {
        AggregationBuilder dateHistogram = AggregationBuilders.dateHistogram(CREATED_AT.getName())
          .field(CREATED_AT.getFieldName())
          .dateHistogramInterval(bucketSize)
          .minDocCount(0L)
          .format(DateUtils.DATETIME_FORMAT)
          .timeZone(DateTimeZone.forOffsetMillis(system.getDefaultTimeZone().getRawOffset()))
          // ES dateHistogram bounds are inclusive while createdBefore parameter is exclusive
          .extendedBounds(new ExtendedBounds(startInclusive ? startTime : (startTime + 1), endTime - 1L));
        addEffortAggregationIfNeeded(query, dateHistogram);
        t.subAggregation(dateHistogram);
      });

    return Optional.of(topAggregation);
  }

  private static DateHistogramInterval computeDateHistogramBucketSize(Duration timeSpan) {
    if (timeSpan.isShorterThan(TWENTY_DAYS)) {
      return DateHistogramInterval.DAY;
    }
    if (timeSpan.isShorterThan(TWENTY_WEEKS)) {
      return DateHistogramInterval.WEEK;
    }
    if (timeSpan.isShorterThan(TWENTY_MONTHS)) {
      return DateHistogramInterval.MONTH;
    }
    return DateHistogramInterval.YEAR;
  }

  private OptionalLong getMinCreatedAt(AllFilters filters) {
    String facetNameAndField = CREATED_AT.getFieldName();
    SearchRequestBuilder esRequest = client
      .prepareSearch(TYPE_ISSUE.getMainType())
      .setSize(0);
    BoolQueryBuilder esFilter = boolQuery();
    filters.stream().filter(Objects::nonNull).forEach(esFilter::must);
    if (esFilter.hasClauses()) {
      esRequest.setQuery(QueryBuilders.boolQuery().filter(esFilter));
    }
    esRequest.addAggregation(AggregationBuilders.min(facetNameAndField).field(facetNameAndField));
    Min minValue = esRequest.get().getAggregations().get(facetNameAndField);

    double actualValue = minValue.getValue();
    if (Double.isInfinite(actualValue)) {
      return OptionalLong.empty();
    }
    return OptionalLong.of((long) actualValue);
  }

  private void addAssignedToMeFacetIfNeeded(SearchOptions options, TopAggregationHelper aggregationHelper, SearchRequestBuilder esRequest) {
    String uuid = userSession.getUuid();
    if (options.getFacets().contains(ASSIGNED_TO_ME.getName()) && !StringUtils.isEmpty(uuid)) {
      AggregationBuilder aggregation = aggregationHelper.buildTermTopAggregation(
        ASSIGNED_TO_ME.getName(),
        ASSIGNED_TO_ME.getTopAggregationDef(),
        ASSIGNED_TO_ME.getNumberOfTerms(),
        NO_EXTRA_FILTER,
        t -> {
          // add sub-aggregation to return issue count for current user
          aggregationHelper.getSubAggregationHelper()
            .buildSelectedItemsAggregation(ASSIGNED_TO_ME.getName(), ASSIGNED_TO_ME.getTopAggregationDef(), new String[] {uuid})
            .ifPresent(t::subAggregation);
        });
      esRequest.addAggregation(aggregation);
    }
  }

  private static void addEffortTopAggregation(TopAggregationHelper aggregationHelper, SearchRequestBuilder esRequest) {
    AggregationBuilder topAggregation = aggregationHelper.buildTopAggregation(
      FACET_MODE_EFFORT,
      EFFORT_TOP_AGGREGATION,
      NO_EXTRA_FILTER,
      t -> t.subAggregation(EFFORT_AGGREGATION));
    esRequest.addAggregation(topAggregation);
  }

  public List<String> searchTags(IssueQuery query, @Nullable String textQuery, int size) {
    Terms terms = listTermsMatching(FIELD_ISSUE_TAGS, query, textQuery, BucketOrder.key(true), size);
    return EsUtils.termsKeys(terms);
  }

  public Map<String, Long> countTags(IssueQuery query, int maxNumberOfTags) {
    Terms terms = listTermsMatching(FIELD_ISSUE_TAGS, query, null, BucketOrder.count(false), maxNumberOfTags);
    return EsUtils.termsToMap(terms);
  }

  public List<String> searchAuthors(IssueQuery query, @Nullable String textQuery, int maxNumberOfAuthors) {
    Terms terms = listTermsMatching(FIELD_ISSUE_AUTHOR_LOGIN, query, textQuery, BucketOrder.key(true), maxNumberOfAuthors);
    return EsUtils.termsKeys(terms);
  }

  private Terms listTermsMatching(String fieldName, IssueQuery query, @Nullable String textQuery, BucketOrder termsOrder, int size) {
    SearchRequestBuilder requestBuilder = client
      .prepareSearch(TYPE_ISSUE.getMainType())
      // Avoids returning search hits
      .setSize(0);

    requestBuilder.setQuery(boolQuery().must(QueryBuilders.matchAllQuery()).filter(createBoolFilter(query)));

    TermsAggregationBuilder aggreg = AggregationBuilders.terms("_ref")
      .field(fieldName)
      .size(size)
      .order(termsOrder)
      .minDocCount(1L);
    if (textQuery != null) {
      aggreg.includeExclude(new IncludeExclude(format(SUBSTRING_MATCH_REGEXP, escapeSpecialRegexChars(textQuery)), null));
    }

    SearchResponse searchResponse = requestBuilder.addAggregation(aggreg).get();
    return searchResponse.getAggregations().get("_ref");
  }

  private BoolQueryBuilder createBoolFilter(IssueQuery query) {
    BoolQueryBuilder boolQuery = boolQuery();
    createAllFilters(query).stream()
      .filter(Objects::nonNull)
      .forEach(boolQuery::must);
    return boolQuery;
  }

  public List<ProjectStatistics> searchProjectStatistics(List<String> projectUuids, List<Long> froms, @Nullable String assigneeUuid) {
    checkState(projectUuids.size() == froms.size(),
      "Expected same size for projectUuids (had size %s) and froms (had size %s)", projectUuids.size(), froms.size());
    if (projectUuids.isEmpty()) {
      return Collections.emptyList();
    }
    SearchRequestBuilder request = client.prepareSearch(TYPE_ISSUE.getMainType())
      .setQuery(
        boolQuery()
          .mustNot(existsQuery(FIELD_ISSUE_RESOLUTION))
          .filter(termQuery(FIELD_ISSUE_ASSIGNEE_UUID, assigneeUuid))
          .mustNot(termQuery(FIELD_ISSUE_TYPE, SECURITY_HOTSPOT.name())))
      .setSize(0);
    IntStream.range(0, projectUuids.size()).forEach(i -> {
      String projectUuid = projectUuids.get(i);
      long from = froms.get(i);
      request
        .addAggregation(AggregationBuilders
          .filter(projectUuid, boolQuery()
            .filter(termQuery(FIELD_ISSUE_PROJECT_UUID, projectUuid))
            .filter(rangeQuery(FIELD_ISSUE_FUNC_CREATED_AT).gte(epochMillisToEpochSeconds(from))))
          .subAggregation(
            AggregationBuilders.terms("branchUuid").field(FIELD_ISSUE_BRANCH_UUID)
              .subAggregation(
                AggregationBuilders.count(AGG_COUNT).field(FIELD_ISSUE_KEY))
              .subAggregation(
                AggregationBuilders.max("maxFuncCreatedAt").field(FIELD_ISSUE_FUNC_CREATED_AT))));
    });
    SearchResponse response = request.get();
    return response.getAggregations().asList().stream()
      .map(x -> (InternalFilter) x)
      .flatMap(projectBucket -> ((StringTerms) projectBucket.getAggregations().get("branchUuid")).getBuckets().stream()
        .flatMap(branchBucket -> {
          long count = ((InternalValueCount) branchBucket.getAggregations().get(AGG_COUNT)).getValue();
          if (count < 1L) {
            return Stream.empty();
          }
          long lastIssueDate = (long) ((InternalMax) branchBucket.getAggregations().get("maxFuncCreatedAt")).getValue();
          return Stream.of(new ProjectStatistics(branchBucket.getKeyAsString(), count, lastIssueDate));
        }))
      .collect(MoreCollectors.toList(projectUuids.size()));
  }

  public List<PrStatistics> searchBranchStatistics(String projectUuid, List<String> branchUuids) {
    if (branchUuids.isEmpty()) {
      return Collections.emptyList();
    }

    SearchRequestBuilder request = client.prepareSearch(TYPE_ISSUE.getMainType())
      .setRouting(AuthorizationDoc.idOf(projectUuid))
      .setQuery(
        boolQuery()
          .must(termsQuery(FIELD_ISSUE_BRANCH_UUID, branchUuids))
          .mustNot(existsQuery(FIELD_ISSUE_RESOLUTION))
          .must(termQuery(FIELD_ISSUE_IS_MAIN_BRANCH, Boolean.toString(false))))
      .setSize(0)
      .addAggregation(AggregationBuilders.terms("branchUuids")
        .field(FIELD_ISSUE_BRANCH_UUID)
        .size(branchUuids.size())
        .subAggregation(AggregationBuilders.terms("types")
          .field(FIELD_ISSUE_TYPE)));
    SearchResponse response = request.get();
    return ((StringTerms) response.getAggregations().get("branchUuids")).getBuckets().stream()
      .map(bucket -> new PrStatistics(bucket.getKeyAsString(),
        ((StringTerms) bucket.getAggregations().get("types")).getBuckets()
          .stream()
          .collect(uniqueIndex(StringTerms.Bucket::getKeyAsString, InternalTerms.Bucket::getDocCount))))
      .collect(MoreCollectors.toList(branchUuids.size()));
  }

  public List<SecurityStandardCategoryStatistics> getSansTop25Report(String projectUuid, boolean isViewOrApp, boolean includeCwe) {
    SearchRequestBuilder request = prepareNonClosedVulnerabilitiesAndHotspotSearch(projectUuid, isViewOrApp);
    Stream.of(SANS_TOP_25_INSECURE_INTERACTION, SANS_TOP_25_RISKY_RESOURCE, SANS_TOP_25_POROUS_DEFENSES)
      .forEach(sansCategory -> request.addAggregation(newSecurityReportSubAggregations(
        AggregationBuilders.filter(sansCategory, boolQuery().filter(termQuery(FIELD_ISSUE_SANS_TOP_25, sansCategory))),
        includeCwe,
        Optional.ofNullable(SecurityStandards.CWES_BY_SANS_TOP_25.get(sansCategory)))));
    return processSecurityReportSearchResults(request, includeCwe);
  }

  public List<SecurityStandardCategoryStatistics> getSonarSourceReport(String projectUuid, boolean isViewOrApp, boolean includeCwe) {
    SearchRequestBuilder request = prepareNonClosedVulnerabilitiesAndHotspotSearch(projectUuid, isViewOrApp);
    Arrays.stream(SecurityStandards.SQCategory.values())
      .forEach(sonarsourceCategory -> request.addAggregation(
        newSecurityReportSubAggregations(
          AggregationBuilders.filter(sonarsourceCategory.getKey(), boolQuery().filter(termQuery(FIELD_ISSUE_SQ_SECURITY_CATEGORY, sonarsourceCategory.getKey()))),
          includeCwe,
          Optional.ofNullable(SecurityStandards.CWES_BY_SQ_CATEGORY.get(sonarsourceCategory)))));
    return processSecurityReportSearchResults(request, includeCwe);
  }

  public List<SecurityStandardCategoryStatistics> getOwaspTop10Report(String projectUuid, boolean isViewOrApp, boolean includeCwe) {
    SearchRequestBuilder request = prepareNonClosedVulnerabilitiesAndHotspotSearch(projectUuid, isViewOrApp);
    IntStream.rangeClosed(1, 10).mapToObj(i -> "a" + i)
      .forEach(owaspCategory -> request.addAggregation(
        newSecurityReportSubAggregations(
          AggregationBuilders.filter(owaspCategory, boolQuery().filter(termQuery(FIELD_ISSUE_OWASP_TOP_10, owaspCategory))),
          includeCwe,
          Optional.empty())));
    return processSecurityReportSearchResults(request, includeCwe);
  }

  private static List<SecurityStandardCategoryStatistics> processSecurityReportSearchResults(SearchRequestBuilder request, boolean includeCwe) {
    SearchResponse response = request.get();
    return response.getAggregations().asList().stream()
      .map(c -> processSecurityReportIssueSearchResults((InternalFilter) c, includeCwe))
      .collect(MoreCollectors.toList());
  }

  private static SecurityStandardCategoryStatistics processSecurityReportIssueSearchResults(InternalFilter categoryBucket, boolean includeCwe) {
    List<SecurityStandardCategoryStatistics> children = new ArrayList<>();
    if (includeCwe) {
      Stream<StringTerms.Bucket> stream = ((StringTerms) categoryBucket.getAggregations().get(AGG_CWES)).getBuckets().stream();
      children = stream.map(cweBucket -> processSecurityReportCategorySearchResults(cweBucket, cweBucket.getKeyAsString(), null)).collect(toList());
    }

    return processSecurityReportCategorySearchResults(categoryBucket, categoryBucket.getName(), children);
  }

  private static SecurityStandardCategoryStatistics processSecurityReportCategorySearchResults(HasAggregations categoryBucket, String categoryName,
    @Nullable List<SecurityStandardCategoryStatistics> children) {
    List<StringTerms.Bucket> severityBuckets = ((StringTerms) ((InternalFilter) categoryBucket.getAggregations().get(AGG_VULNERABILITIES)).getAggregations().get(AGG_SEVERITIES))
      .getBuckets();
    long vulnerabilities = severityBuckets.stream().mapToLong(b -> ((InternalValueCount) b.getAggregations().get(AGG_COUNT)).getValue()).sum();
    // Worst severity having at least one issue
    OptionalInt severityRating = severityBuckets.stream()
      .filter(b -> ((InternalValueCount) b.getAggregations().get(AGG_COUNT)).getValue() != 0)
      .mapToInt(b -> Severity.ALL.indexOf(b.getKeyAsString()) + 1)
      .max();

    long toReviewSecurityHotspots = ((InternalValueCount) ((InternalFilter) categoryBucket.getAggregations().get(AGG_TO_REVIEW_SECURITY_HOTSPOTS)).getAggregations().get(AGG_COUNT))
      .getValue();
    long reviewedSecurityHotspots = ((InternalValueCount) ((InternalFilter) categoryBucket.getAggregations().get(AGG_REVIEWED_SECURITY_HOTSPOTS)).getAggregations().get(AGG_COUNT))
      .getValue();

    return new SecurityStandardCategoryStatistics(categoryName, vulnerabilities, severityRating, toReviewSecurityHotspots,
      reviewedSecurityHotspots, children);
  }

  private static AggregationBuilder newSecurityReportSubAggregations(AggregationBuilder categoriesAggs, boolean includeCwe, Optional<Set<String>> cwesInCategory) {
    AggregationBuilder aggregationBuilder = addSecurityReportIssueCountAggregations(categoriesAggs);
    if (includeCwe) {
      final TermsAggregationBuilder cwesAgg = AggregationBuilders.terms(AGG_CWES)
        .field(FIELD_ISSUE_CWE)
        // 100 should be enough to display all CWEs. If not, the UI will be broken anyway
        .size(MAX_FACET_SIZE);
      cwesInCategory.ifPresent(set -> {
        cwesAgg.includeExclude(new IncludeExclude(set.toArray(new String[0]), new String[0]));
      });
      categoriesAggs.subAggregation(addSecurityReportIssueCountAggregations(cwesAgg));
    }
    return aggregationBuilder;
  }

  private static AggregationBuilder addSecurityReportIssueCountAggregations(AggregationBuilder categoryAggs) {
    return categoryAggs
      .subAggregation(
        AggregationBuilders.filter(AGG_VULNERABILITIES, NON_RESOLVED_VULNERABILITIES_FILTER)
          .subAggregation(
            AggregationBuilders.terms(AGG_SEVERITIES).field(FIELD_ISSUE_SEVERITY)
              .subAggregation(
                AggregationBuilders.count(AGG_COUNT).field(FIELD_ISSUE_KEY))))
      .subAggregation(AggregationBuilders.filter(AGG_TO_REVIEW_SECURITY_HOTSPOTS, TO_REVIEW_HOTSPOTS_FILTER)
        .subAggregation(
          AggregationBuilders.count(AGG_COUNT).field(FIELD_ISSUE_KEY)))
      .subAggregation(AggregationBuilders.filter(AGG_IN_REVIEW_SECURITY_HOTSPOTS, IN_REVIEW_HOTSPOTS_FILTER)
        .subAggregation(
          AggregationBuilders.count(AGG_COUNT).field(FIELD_ISSUE_KEY)))
      .subAggregation(AggregationBuilders.filter(AGG_REVIEWED_SECURITY_HOTSPOTS, REVIEWED_HOTSPOTS_FILTER)
        .subAggregation(
          AggregationBuilders.count(AGG_COUNT).field(FIELD_ISSUE_KEY)));
  }

  private SearchRequestBuilder prepareNonClosedVulnerabilitiesAndHotspotSearch(String projectUuid, boolean isViewOrApp) {
    BoolQueryBuilder componentFilter = boolQuery();
    if (isViewOrApp) {
      IndexType.IndexMainType mainType = TYPE_VIEW;
      componentFilter.filter(QueryBuilders.termsLookupQuery(FIELD_ISSUE_BRANCH_UUID,
        new TermsLookup(
          mainType.getIndex().getName(),
          mainType.getType(),
          projectUuid,
          ViewIndexDefinition.FIELD_PROJECTS)));
    } else {
      componentFilter.filter(termQuery(FIELD_ISSUE_BRANCH_UUID, projectUuid));
    }
    return client.prepareSearch(TYPE_ISSUE.getMainType())
      .setQuery(
        componentFilter
          .should(NON_RESOLVED_VULNERABILITIES_FILTER)
          .should(TO_REVIEW_HOTSPOTS_FILTER)
          .should(IN_REVIEW_HOTSPOTS_FILTER)
          .should(REVIEWED_HOTSPOTS_FILTER)
          .minimumShouldMatch(1))
      .setSize(0);
  }

}
