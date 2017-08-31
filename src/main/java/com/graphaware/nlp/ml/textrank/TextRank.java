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

import static com.graphaware.nlp.domain.Labels.Keyword;
import static com.graphaware.nlp.domain.Relationships.DESCRIBES;
import com.graphaware.nlp.util.MapUtil;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TextRank {

    private static final Logger LOG = LoggerFactory.getLogger(TextRank.class);
    private final GraphDatabaseService database;
    private boolean removeStopWords;
    private boolean directionMatters;
    private boolean respectSentences;
    private boolean useTfIdfWeights;
    private int cooccurrenceWindow;
    private List<String> stopWords;
    private List<String> admittedPOSs;
    private Map<Long, String> idToValue = new HashMap<>();

    public TextRank(GraphDatabaseService database) {
        this.database = database;
        this.stopWords = Arrays.asList("new", "old", "large", "big", "small", "many", "few", "best", "worst");
        this.removeStopWords = false;
        this.directionMatters = false;
        this.respectSentences = false;
        this.useTfIdfWeights = false;
        this.cooccurrenceWindow = 2;
        this.admittedPOSs = Arrays.asList("NN", "NNS", "NNP", "NNPS", "JJ", "JJR", "JJS", "Unknown"); // "Unknown" because we want to keep tags that have unknown POS
    }

    public void setStopwords(String stopwords) {
        this.stopWords = Arrays.asList(stopwords.split(",")).stream().map(str -> str.trim().toLowerCase()).collect(Collectors.toList());
        this.removeStopWords = true;
    }

    public void removeStopWords(boolean val) {
        this.removeStopWords = val;
    }

    public void respectDirections(boolean val) {
        this.directionMatters = val;
    }

    public void respectSentences(boolean val) {
        this.respectSentences = val;
    }
    public void useTfIdfWeights(boolean val) {
        this.useTfIdfWeights = val;
    }

    public void setCooccurrenceWindow(int val) {
        this.cooccurrenceWindow = val;
    }

    @Deprecated
    public boolean createCooccurrencesOld(Node annotatedText, String relType, String relWeight) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", annotatedText.getId());
        params.put("relType", relType);
        params.put("relWeight", relWeight);
        try (Transaction tx = database.beginTx();) {
            database.execute("MATCH (a:AnnotatedText)-[:CONTAINS_SENTENCE]->(s:Sentence)-[:SENTENCE_TAG_OCCURRENCE]->(to:TagOccurrence)\n"
                    + "WHERE a.id = {id} \n"
                    + "WITH s, to\n"
                    + "ORDER BY s.sentenceNumber, to.startPosition\n"
                    + "MATCH (to)-[:TAG_OCCURRENCE_TAG]->(t:Tag)\n"
                    + "WHERE size(t.value) > 2 AND ANY (p in t.pos where p in ['NN', 'NNS', 'NNP', 'NNPS', 'JJ', 'JJR', 'JJS'] )\n" // only nouns and adjectives
                    + "WITH s, collect(t) as tags\n"
                    + "UNWIND range(0, size(tags) - 2, 1) as i\n"
                    + "WITH s, tags[i] as tag1, tags[i+1] as tag2\n"
                    + "MERGE (tag1)-[c:" + relType + "]-(tag2)\n"
                    + "ON CREATE SET c.weight = 1\n"
                    + "ON MATCH SET c.weight = c.weight + 1\n",
                    params);
            tx.success();
        } catch (Exception e) {
            LOG.error("createCooccurrences() failed with QueryExecutionException: " + e.getMessage());
            return false;
        }
        return true;
    }

    public Map<Long, Map<Long, CoOccurrenceItem>> createCooccurrences(Node annotatedText) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", annotatedText.getId());

        // query based on orignal TextRank: it doesn't care about sentence boundaries (it connects last word of a sentence with 1st word of the next sentence)
        String query = "MATCH (a:AnnotatedText)-[:CONTAINS_SENTENCE]->(s:Sentence)-[:SENTENCE_TAG_OCCURRENCE]->(to:TagOccurrence)\n"
                + "WHERE id(a) = {id} \n"
                + "WITH to\n"
                + "ORDER BY to.startPosition\n"
                + "MATCH (to)-[:TAG_OCCURRENCE_TAG]->(t:Tag)\n"
                + "WHERE size(t.value) > 2\n"
                + "WITH collect(t) as tags\n"
                + "UNWIND range(0, size(tags) - 2, 1) as i\n"
                + "RETURN id(tags[i]) as tag1, id(tags[i+1]) as tag2, tags[i].value as tag1_val, tags[i+1].value as tag2_val, "
                    + "(case tags[i].pos when null then 'Unknown,' else reduce(pos='', p in tags[i].pos | pos+p+',') end) as pos1, (case tags[i+1].pos when null then 'Unknown,' else reduce(pos='', p in tags[i+1].pos | pos+p+',') end) as pos2\n";

        // a query for creating co-occurrences per sentence
        if (respectSentences) {
            query = "MATCH (a:AnnotatedText)-[:CONTAINS_SENTENCE]->(s:Sentence)-[:SENTENCE_TAG_OCCURRENCE]->(to:TagOccurrence)\n"
                + "WHERE id(a) = {id} \n"
                + "WITH s, to\n"
                + "ORDER BY s.sentenceNumber, to.startPosition\n"
                + "MATCH (to)-[:TAG_OCCURRENCE_TAG]->(t:Tag)\n"
                + "WHERE size(t.value) > 2\n"
                + "WITH s, collect(t) as tags\n"
                + "ORDER BY s.sentenceNumber\n"
                + "UNWIND range(0, size(tags) - 2, 1) as i\n"
                + "RETURN s, id(tags[i]) as tag1, id(tags[i+1]) as tag2, tags[i].value as tag1_val, tags[i+1].value as tag2_val, "
                    + "(case tags[i].pos when null then 'Unknown,' else reduce(pos='', p in tags[i].pos | pos+p+',') end) as pos1, (case tags[i+1].pos when null then 'Unknown,' else reduce(pos='', p in tags[i+1].pos | pos+p+',') end) as pos2\n";
        }

        Result res = database.execute(query, params);

        Map<Long, Map<Long, CoOccurrenceItem>> results = new HashMap<>();
        Long previous1 = -1L;
        int n_skips = 1;
        while (res != null && res.hasNext()) {
            Map<String, Object> next = res.next();
            Long tag1 = (Long) next.get("tag1");
            Long tag2 = (Long) next.get("tag2");
            List<String> pos1 = Arrays.asList( ((String) next.get("pos1")).split(",") );
            List<String> pos2 = Arrays.asList( ((String) next.get("pos2")).split(",") );

            // check whether POS of both tags are admitted
            boolean bPOS1 = pos1.stream().filter(pos -> pos!=null && admittedPOSs.contains(pos)).count()!=0;
            boolean bPOS2 = pos2.stream().filter(pos -> pos!=null && admittedPOSs.contains(pos)).count()!=0;

            // fill tag co-occurrences (adjacency matrix)
            //   * window of words N = 2 (i.e. neighbours only)
            if (bPOS1 && bPOS2) {
                addTagToCoOccurrence(results, tag1, tag2);
                if (!directionMatters) // when direction of co-occurrence relationships is not important
                    addTagToCoOccurrence(results, tag2, tag1);
                //LOG.info("Adding co-occurrence: " + (String) next.get("tag1_val") + " -> " + (String) next.get("tag2_val"));
                n_skips = 1;
            }
            //   * window of words N > 2
            else if (bPOS2) {
                if (n_skips<cooccurrenceWindow) {
                    addTagToCoOccurrence(results, previous1, tag2);
                    if (!directionMatters)
                        addTagToCoOccurrence(results, tag2, previous1);
                    //LOG.info("  window N=" + (n_skips+1) + " co-occurrence: " + idToValue.get(previous1) + " -> " + (String) next.get("tag2_val"));
                }
                n_skips = 1;
            } else {    
                n_skips++;
                if (bPOS1) previous1 = tag1;
            }

            // for logging purposses
            idToValue.put(tag1, (String) next.get("tag1_val"));
            idToValue.put(tag2, (String) next.get("tag2_val"));
        }
        return results;
    }

    private void addTagToCoOccurrence(Map<Long, Map<Long, CoOccurrenceItem>> results, Long tag1, Long tag2) {
        Map<Long, CoOccurrenceItem> mapTag1;
        if (!results.containsKey(tag1)) {
            mapTag1 = new HashMap<>();
            results.put(tag1, mapTag1);
        } else {
            mapTag1 = results.get(tag1);
        }
        if (mapTag1.containsKey(tag2)) {
            mapTag1.get(tag2).incCount();
        } else {
            mapTag1.put(tag2, new CoOccurrenceItem(tag1, tag2));
        }
    }

    @Deprecated
    public boolean deleteCooccurrences(Long annotatedID, String relType) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", annotatedID);
        try (Transaction tx = database.beginTx();) {
            database.execute("MATCH (a:AnnotatedText)-[:CONTAINS_SENTENCE]->(s:Sentence)\n"
                    + "WHERE a.id = {id}\n"
                    + "MATCH (s)-[:HAS_TAG]->(:Tag)-[co:" + relType + "]->(:Tag)\n"
                    + "DELETE co",
                    params);
            tx.success();
        } catch (Exception e) {
            LOG.error("deleteCooccurrences() failed with QueryExecutionException: " + e.getMessage());
            return false;
        }
        return true;
    }

    public boolean evaluate(Node annotatedText, Map<Long, Map<Long, CoOccurrenceItem>> coOccurrence, int iter, double damp, double threshold) {
        PageRank pageRank = new PageRank(database);
        pageRank.respectDirections(directionMatters);
        if (useTfIdfWeights)
            pageRank.setNodeWeights( initializeNodeWeights_TfIdf(annotatedText, coOccurrence) );
        Map<Long, Double> pageRanks = pageRank.run(coOccurrence, iter, damp, threshold);
        int n_oneThird = (int) (pageRanks.size()/3.0f);
        n_oneThird = n_oneThird > 30 ? 30 : n_oneThird;
        List<Long> topx = getTopX(pageRanks, n_oneThird);

        //pageRanks.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
        //    .forEach(en -> LOG.info(idToValue.get(en.getKey()) + ": " + en.getValue()));
        //LOG.info("Sum of PageRanks = " + pageRanks.values().stream().mapToDouble(Number::doubleValue).sum());
        String topStr = "";
        for (Long id: topx) {
            topStr += idToValue.get(id) + ", ";
        }
        LOG.info("Top " + n_oneThird + " tags: " + topStr);

        Map<String, Object> params = new HashMap<>();
        params.put("id", annotatedText.getId());
        params.put("nodeList", topx);
        params.put("posList", admittedPOSs);
        Result res = database.execute(
                "MATCH (node:Tag)<-[:TAG_OCCURRENCE_TAG]-(to:TagOccurrence)<-[:SENTENCE_TAG_OCCURRENCE]-(:Sentence)<-[:CONTAINS_SENTENCE]-(a:AnnotatedText)\n"
                + "WHERE id(a) = {id} and id(node) in {nodeList}\n"
                + "OPTIONAL MATCH (to)-[:COMPOUND|AMOD]-(to2:TagOccurrence)-[:TAG_OCCURRENCE_TAG]->(t2:Tag)\n"
                + "WHERE not exists(t2.pos) or (t2.pos in {posList})\n"
                + "RETURN node.id as tag, to.startPosition as sP, to.endPosition as eP, id(node) as tagId, collect(t2.value) as rel_tags, collect(to2.startPosition) as rel_tos,  collect(to2.endPosition) as rel_toe\n"
                + "ORDER BY sP asc",
                params);

        // First: merge neighboring words into phrases
        long prev_eP = -1000;
        String prev_tag = "";
        String lang = "";
        Map<Integer, String> keyphrase = new HashMap<>();
        Map<String, Integer> results = new HashMap<>();
        Map<String, Double> keywords = new HashMap<>();
        Map<String, List<WordItem>> dependencies = new HashMap<>();
        while (res != null && res.hasNext()) {
            Map<String, Object> next = res.next();
            Long tagId = (Long) next.get("tagId");
            //if (!topx.contains(tagId))
            //    continue;
            int startPosition = (int) next.get("sP");
            int endPosition = (int) next.get("eP");

            List<String> rel_tags = iterableToList( (Iterable<String>) next.get("rel_tags") );
            List<Integer> rel_tos = iterableToList( (Iterable<Integer>) next.get("rel_tos") );
            List<Integer> rel_toe = iterableToList( (Iterable<Integer>) next.get("rel_toe") );
            List<WordItem> rel_dep = new ArrayList<>();
            for (int i=0; i<rel_tags.size(); i++)  {
                rel_dep.add(new WordItem(rel_tos.get(i), rel_toe.get(i), rel_tags.get(i)));
            }

            Double score = pageRanks.get(tagId);
            String tag = (String) next.get("tag");
            
            final String[] tagSplit = tag.split("_");
            if (tagSplit.length > 2) {
                LOG.warn("Tag " + tag + " has more than 1 underscore symbols");
            }
            
            String tagVal = tagSplit[0];
            lang = tagSplit[1];

            if (removeStopWords && stopWords.stream().anyMatch(str -> str.equals(tagVal))) {
                continue;
            }
            keywords.put(tag, score);
            
            if (startPosition - prev_eP <= 1 && dependencies.containsKey(prev_tag)) {
                List<WordItem> merged = new ArrayList<>(dependencies.get(prev_tag));
                merged.retainAll(rel_dep); // 'merged' now contains only elements that are shared between the two lists
                if (merged.size() > 0 || dependencies.get(prev_tag).stream().filter(wi -> wi.getWord().equals(tagVal)).count() > 0) {
                    keyphrase.put(startPosition, tagVal);
                    dependencies.put(tagVal, rel_dep);
                }
            } else {
                addKeyphraseToResults(keyphrase, dependencies, prev_eP, lang, results);

                keyphrase.clear();
                keyphrase.put(startPosition, tagVal);
                dependencies.clear();
                dependencies.put(tagVal, rel_dep);
            }
            prev_eP = endPosition;
            prev_tag = tagVal;
            
        }

        addKeyphraseToResults(keyphrase, dependencies, prev_eP, lang, results);

        // Next: include into final result simply top-x single words
        keywords.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .limit(n_oneThird > 10 ? 10 : n_oneThird)
                .forEach(en -> {
                    results.put(en.getKey(), 1);
                    LOG.debug(en.getKey());
                });

        LOG.info("--- Results: ");
        results.keySet().stream().map((key) -> {
            if (key.split("_").length > 2) {
                LOG.warn("Tag " + key + " has more than 1 underscore symbols, newly created Keyword node might be wrong");
            }
            return key;
        }).forEachOrdered((key) -> {
            String val = key.split("_")[0];
            if (!(removeStopWords && stopWords.stream().anyMatch(str -> str.equals(val)))) {
                Node newNode;
                ResourceIterator<Node> findNodes = database.findNodes(Keyword, "id", key);
                if (findNodes.hasNext()) {
                    newNode = findNodes.next();
                } else {
                    newNode = database.createNode(Keyword);
                    newNode.setProperty("id", key);
                    newNode.setProperty("value", val);
                }
                if (newNode != null) {
                    Relationship rel = newNode.createRelationshipTo(annotatedText, DESCRIBES);
                    rel.setProperty("count", results.get(key));
                }
                LOG.info(val);
            }
        });

        return true;
    }

    private void addKeyphraseToResults(Map<Integer, String> keyphrase, Map<String, List<WordItem>> dependencies, long prev_eP, String lang, Map<String, Integer> results) {
        if (keyphrase.size() > 1) {
            // append dependency word if it's missing
            List<WordItem> missing_words = new ArrayList<>();
            for (String key: dependencies.keySet()) {
                missing_words.addAll(dependencies.get(key));
            }
            missing_words.removeAll(keyphrase.values());
            missing_words.stream()
                //.filter(wi -> keyphrase.entrySet().stream().filter(en -> (en.getKey() - wi.getEnd()) <= 1 || (wi.getStart() - prev_eP) <= 1).count() > 0 ) // don't use `<=` only - the left hand side can be negative!
                .filter(wi -> keyphrase.entrySet().stream().filter(en -> (en.getKey() - wi.getEnd()) == 1 || (wi.getStart() - prev_eP) == 1).count() > 0 )
                .forEach(wi -> keyphrase.put(wi.getStart(), wi.getWord()));

            // store keyphrase into 'results'
            String key = keyphrase.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(en -> en.getValue())
                .collect(Collectors.joining(" "));
            key += "_" + lang;

            //String key = String.join(, " ") + "_" + lang;
            if (results.containsKey(key)) {
                results.put(key, results.get(key) + 1);
            } else {
                results.put(key, 1);
            }
        }

    }

    private List<Long> getTopX(Map<Long, Double> pageRanks, int x) {
        List<Long> topx = pageRanks.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
            .limit(x)
            .map((item) -> item.getKey()).collect(Collectors.toList());
        return topx;
    }

    private Map<Long, Double> initializeNodeWeights_TfIdf(Node annotatedText, Map<Long, Map<Long, CoOccurrenceItem>> coOccurrences) {
        Map<Long, Double> nodeWeights = new HashMap<Long, Double>();
        coOccurrences.entrySet().stream().forEach((coOccurrence) -> {
            coOccurrence.getValue().entrySet().stream().forEach((entry) -> {
                nodeWeights.put(entry.getValue().getSource(), 1.0d);
                nodeWeights.put(entry.getValue().getDestination(), 1.0d);
            });
        });

        String query = "MATCH (doc:AnnotatedText)\n"
            + "WITH count(doc) as documentsCount\n"
            + "MATCH (a:AnnotatedText)-[:CONTAINS_SENTENCE]->(:Sentence)-[ht:HAS_TAG]->(t:Tag)\n"
            + "WHERE id(a) = " + annotatedText.getId() + "\n"
            + "WITH t, sum(ht.tf) as tf, documentsCount\n"
            + "MATCH (a:AnnotatedText)-[:CONTAINS_SENTENCE]->(:Sentence)-[:HAS_TAG]->(t)\n"
            + "RETURN id(t) as tag, t.value as tagVal, tf, count(distinct a) as docCountForTag, documentsCount\n";

        try (Transaction tx = database.beginTx();) {
            Result res = database.execute(query);
            while (res != null && res.hasNext()) {
                Map<String, Object> next = res.next();
                Long tag = (Long) next.get("tag");
                if (!nodeWeights.keySet().contains(tag)) // initialize only those that are needed!
                    continue;
                long tf = ((Long) next.get("tf")).longValue();

                long docCount = (long) next.get("documentsCount");
                long docCountTag = (long) next.get("docCountForTag");
                double idf = Double.valueOf(Math.log10(Double.valueOf(1.0f*docCount/docCountTag).doubleValue())).floatValue();

                //nodeWeights.put(tag, tf*idf);
                nodeWeights.put(tag, idf);
                //LOG.info((String) next.get("tagVal") + ": tf = " + tf + ", idf = " + idf + " (docCountTag = " + docCountTag + "), tf*idf = " + tf*idf);
            }
            tx.success();
        } catch (Exception e) {
            LOG.error("Error while initializing node weights: ", e);
            return nodeWeights;
        }

        return nodeWeights;
    }

    public boolean postprocess(String keywordsLabel) {
        //Map<String, Object> params = new HashMap<>();
        //params.put("Keyword", keywordsLabel);

        String query = "match (k:" + keywordsLabel + ")\n"
            + "where size(split(k.value, \" \")) > 1\n"
            + "with k, split(k.value, \" \") as ks_orig\n"
            + "match (k2:" + keywordsLabel + ")\n"
            + "where size(split(k2.value, \" \")) > size(ks_orig)\n"
            + "with ks_orig, k2, k, split(k2.value, \" \") as ks_check\n"
            + "where all(el in ks_orig where el in ks_check)\n"
            + "match (k2)-[:DESCRIBES]->(a:AnnotatedText)\n"
            + "merge (k)-[r:DESCRIBES]->(a)";

        try (Transaction tx = database.beginTx();) { 
            database.execute(query);
            tx.success();
        } catch (Exception e) {
            LOG.error("Error while running TextRank post-processing: ", e);
            return false;
        }

        return true;
    }

    private <T> List<T> iterableToList(Iterable<T> it) {
        List<T> newList = new ArrayList<>();
        for (T obj: it) {
            newList.add(obj);
        }
        return newList;
    }

}
