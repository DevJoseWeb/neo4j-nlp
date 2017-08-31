/*
 * Copyright (c) 2013-2016 GraphAware
 *
 * This file is part of the GraphAware Framework.
 *
 * GraphAware Framework is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details. You should have received a copy of
 * the GNU General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.graphaware.nlp.ml.textrank;

import com.graphaware.nlp.procedure.NLPProcedure;
import java.util.Collections;
import java.util.Map;
import org.neo4j.collection.RawIterator;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.helpers.collection.Iterators;
import org.neo4j.kernel.api.exceptions.ProcedureException;
import org.neo4j.kernel.api.proc.CallableProcedure;
import org.neo4j.kernel.api.proc.Neo4jTypes;
import org.neo4j.kernel.api.proc.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.neo4j.kernel.api.proc.ProcedureSignature.procedureSignature;
import org.neo4j.procedure.Mode;

public class TextRankProcedure extends NLPProcedure {

    private static final Logger LOG = LoggerFactory.getLogger(TextRankProcedure.class);

    private final static String PARAMETER_NAME_NODE = "node";
    private final static String PARAMETER_NAME_SCORE = "score";
    private final static String PARAMETER_NAME_NODEW = "node_w";

    //private final static String PARAMETER_NAME_QUERY = "query";
    private final static String PARAMETER_ANNOTATED_TEXT = "annotatedText";
    private final static String PARAMETER_NODE_TYPE = "nodeType";
    private final static String PARAMETER_RELATIONSHIP_TYPE = "relationshipType";
    private final static String PARAMETER_RELATIONSHIP_WEIGHT = "relationshipWeight";
    private final static String PARAMETER_ITERATIONS = "iter";
    private final static String PARAMETER_DAMPING_FACTOR = "damp";
    private final static String PARAMETER_DAMPING_THRESHOLD = "threshold";
    private final static String PARAMETER_STOPWORDS = "stopwords";
    private final static String PARAMETER_DO_STOPWORDS = "removeStopWords";
    private final static String PARAMETER_RESPECT_DIRECTIONS = "respectDirections";
    private final static String PARAMETER_RESPECT_SENTENCES = "respectSentences";
    private final static String PARAMETER_USE_TFIDF_WEIGHTS = "useTfIdfWeights";
    private final static String PARAMETER_COOCCURRENCE_WINDOW = "cooccurrenceWindow";
    private final static String PARAMETER_KEYWORDS_LABEL = "keywordsLabel";

    private static final long DEFAULT_ITERATIONS = 30;
    private static final double DEFAULT_DUMPING_FACTOR = 0.85;
    private static final double DEFAULT_THRESHOLD = 0.0001;
    private static final String DEFAULT_NODE_TYPE = "Tag";
    private static final String DEFAULT_CO_OCCURRENCE_RELATIONTHIP = "CO_OCCURRENCE";
    private static final String DEFAULT_WEIGHT_PROPERTY = "weight";
    private static final boolean DEFAULT_STOPWORDS_ENABLING = false;
    private static final boolean DEFAULT_RESPECT_DIRECTIONS_TEXTRANK = false;
    private static final boolean DEFAULT_RESPECT_DIRECTIONS_PAGERANK = true;
    private static final boolean DEFAULT_RESPECT_SENTENCES = false;
    private static final boolean DEFAULT_USE_TFIDF_WEIGHTS = false;
    private static final long DEFAULT_COOCCURRENCE_WINDOW = 2;
    private static final String DEFAULT_KEYWORDS_LABEL = "Keyword";

    private final GraphDatabaseService database;

    public TextRankProcedure(GraphDatabaseService database) {
        this.database = database;
    }

    public CallableProcedure.BasicProcedure computePageRank() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("ml", "textrank", "computePageRank"))
                .mode(Mode.WRITE)
                .in(PARAMETER_NAME_INPUT, Neo4jTypes.NTAny)
                .out("result", Neo4jTypes.NTString).build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(Context ctx, Object[] input) throws ProcedureException {
                Map<String, Object> inputParams = (Map) input[0];
                String nodeType = (String) inputParams.getOrDefault(PARAMETER_NODE_TYPE, DEFAULT_NODE_TYPE);
                String relType = (String) inputParams.getOrDefault(PARAMETER_RELATIONSHIP_TYPE, DEFAULT_CO_OCCURRENCE_RELATIONTHIP);
                String relWeight = (String) inputParams.getOrDefault(PARAMETER_RELATIONSHIP_WEIGHT, DEFAULT_WEIGHT_PROPERTY);
                int iter = ((Long) inputParams.getOrDefault(PARAMETER_ITERATIONS, DEFAULT_ITERATIONS)).intValue();
                double damp = (double) inputParams.getOrDefault(PARAMETER_DAMPING_FACTOR, DEFAULT_DUMPING_FACTOR);
                double threshold = (double) inputParams.getOrDefault(PARAMETER_DAMPING_THRESHOLD, DEFAULT_THRESHOLD);
                boolean respectDirections = (boolean) inputParams.getOrDefault(PARAMETER_RESPECT_DIRECTIONS, DEFAULT_RESPECT_DIRECTIONS_PAGERANK);

                String result = "success";
                PageRank pagerank = new PageRank(database);
                pagerank.respectDirections(respectDirections);
                Map<Long, Map<Long, CoOccurrenceItem>> coOccurrences = pagerank.processGraph(nodeType, relType, relWeight);
                if (coOccurrences.isEmpty()) {
                    result = "failure";
                    return Iterators.asRawIterator(Collections.<Object[]>singleton(new Object[]{result}).iterator());
                }
                Map<Long, Double> pageranks = pagerank.run(coOccurrences, iter, damp, threshold);
                if (pageranks.isEmpty()) {
                    result = "failure";
                    return Iterators.asRawIterator(Collections.<Object[]>singleton(new Object[]{result}).iterator());
                }
                pageranks.entrySet().stream().forEach(en -> LOG.info("PR(" + en.getKey() + ") = " + en.getValue()));
                LOG.info("Sum of PageRanks: " + pageranks.values().stream().mapToDouble(Number::doubleValue).sum());
                pagerank.storeOnGraph(pageranks, nodeType);

                return Iterators.asRawIterator(Collections.<Object[]>singleton(new Object[]{result}).iterator());
            }
        };
    }

    public CallableProcedure.BasicProcedure compute() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("ml", "textrank", "compute"))
                .mode(Mode.WRITE)
                .in(PARAMETER_NAME_INPUT, Neo4jTypes.NTAny)
                .out("result", Neo4jTypes.NTString).build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(Context ctx, Object[] input) throws ProcedureException {
                Map<String, Object> inputParams = (Map) input[0];
                if (!inputParams.containsKey(PARAMETER_ANNOTATED_TEXT)) {
                    LOG.error("Missing parameter \'" + PARAMETER_ANNOTATED_TEXT + "\'");
                    return Iterators.asRawIterator(Collections.<Object[]>singleton(new String[]{"failure"}).iterator());
                }
                Node annotatedText = (Node) inputParams.get(PARAMETER_ANNOTATED_TEXT);
                int iter = ((Long) inputParams.getOrDefault(PARAMETER_ITERATIONS, DEFAULT_ITERATIONS)).intValue();
                double damp = (double) inputParams.getOrDefault(PARAMETER_DAMPING_FACTOR, DEFAULT_DUMPING_FACTOR);
                double threshold = (double) inputParams.getOrDefault(PARAMETER_DAMPING_THRESHOLD, DEFAULT_THRESHOLD);
                boolean doStopwords = (boolean) inputParams.getOrDefault(PARAMETER_DO_STOPWORDS, DEFAULT_STOPWORDS_ENABLING);
                boolean respectDirections = (boolean) inputParams.getOrDefault(PARAMETER_RESPECT_DIRECTIONS, DEFAULT_RESPECT_DIRECTIONS_TEXTRANK);
                boolean respectSentences = (boolean) inputParams.getOrDefault(PARAMETER_RESPECT_SENTENCES, DEFAULT_RESPECT_SENTENCES);
                boolean useTfIdfWeights = (boolean) inputParams.getOrDefault(PARAMETER_USE_TFIDF_WEIGHTS, DEFAULT_USE_TFIDF_WEIGHTS);
                int cooccurrenceWindow = ((Long) inputParams.getOrDefault(PARAMETER_COOCCURRENCE_WINDOW, DEFAULT_COOCCURRENCE_WINDOW)).intValue();

                TextRank textrank = new TextRank(database);

                if (inputParams.containsKey(PARAMETER_STOPWORDS)) {
                    textrank.setStopwords((String) inputParams.get(PARAMETER_STOPWORDS));
                }
                textrank.removeStopWords(doStopwords);
                textrank.respectDirections(respectDirections);
                textrank.respectSentences(respectSentences);
                textrank.useTfIdfWeights(useTfIdfWeights);
                textrank.setCooccurrenceWindow(cooccurrenceWindow);

                String resReport = "success";

                Map<Long, Map<Long, CoOccurrenceItem>> coOccurrence = textrank.createCooccurrences(annotatedText);
                boolean res = textrank.evaluate(annotatedText, coOccurrence, iter, damp, threshold);

                if (!res) {
                    resReport = "failure";
                }

                LOG.info("AnnotatedText with ID " + annotatedText.getId() + " processed.");

                return Iterators.asRawIterator(Collections.<Object[]>singleton(new Object[]{resReport}).iterator());
            }
        };
    }

    public CallableProcedure.BasicProcedure postprocess() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("ml", "textrank", "postprocess"))
                .mode(Mode.WRITE)
                .in(PARAMETER_NAME_INPUT, Neo4jTypes.NTAny)
                .out("result", Neo4jTypes.NTString).build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(Context ctx, Object[] input) throws ProcedureException {
                Map<String, Object> inputParams = (Map) input[0];
                String kwLabel = (String) inputParams.getOrDefault(PARAMETER_KEYWORDS_LABEL, DEFAULT_KEYWORDS_LABEL);

                LOG.info("Starting TextRank post-processing ...");

                String resReport = "success";

                TextRank textrank = new TextRank(database);
                if (!textrank.postprocess(kwLabel))
                    resReport = "failure";

                return Iterators.asRawIterator(Collections.<Object[]>singleton(new Object[]{resReport}).iterator());
            }
        };
    }

}
