package lernt;

import org.neo4j.graphdb.*;
import org.neo4j.logging.Log;
import org.neo4j.procedure.*;

import java.util.*;
import java.util.stream.Stream;

/**
 * CoursePath
 * A custom neo4j extension ("procedure") for lernt.io to recommend learning paths based on
 * the most recommended user-built learning paths
 */
public class CoursePath
{
    @Context
    public GraphDatabaseService db;

    @Context
    public Log log;


    /**
     * APOC procedure entry point
     * @param startNode   The start node on which we start to recurse; could be Course or Track
     * @param config      The configuration parameters object
     * @return            A sub-graph containing all courses
     * @throws Exception  If procedure call fails
     */
    @Procedure(name = "lernt.findCoursePath", mode=Mode.SCHEMA)
    public Stream<GraphResult> findCoursePath(@Name("startNode") Node startNode,
                                              @Name("config") Map<String, Object> config) throws Exception
    {
        ConfigObject configuration = new ConfigObject(config);

        // TODO: Arguably getting all user courses could be done in tracker
        Set<Course> completedCourses = getAllUserCourses(db, configuration);
        Tracker tracker = new Tracker(db, configuration, completedCourses);
        ResultNode start = new ResultNode(startNode, configuration, db);
        tracker.addToResultNodes(start);

        // Use a queue for breadth-first recursion over the tree
        Queue<ResultNode> q = new LinkedList<>();

        findCoursePathPrivate(start, tracker, configuration, q);

        // Nothing found
        if (tracker.getResultNodesSize() <= 1) {
            return Stream.empty();
        }

        tracker.buildHead(db);

        List<Node> nodes = tracker.getResultNodesList();
        List<Relationship> relationships = tracker.getResultRelsList();

        return Stream.of(new GraphResult(nodes, relationships));
    }


    /**
     * Navigates the graph breadth-first, adding relevant courses to the result set
     * @param current     The current node in recursion
     * @param tracker     The tracker keeping recursion state
     * @param config      The configuration object
     * @param q           A queue to enable breadth-first recursion
     * @throws Exception  In a variety of scenarios in which graph navigation may fail
     */
    private void findCoursePathPrivate(ResultNode current, Tracker tracker, ConfigObject config, Queue<ResultNode> q)
            throws Exception
    {
        // Get all incoming candidates
        // The candidate decider filters out courses which should not be added to the result set
        CandidateDecider cd = new CandidateDecider(current, tracker, config);
        Set<Course> candidateSet = cd.getCandidateSet();

        // For every viable candidate, add to result set as long as it doesn't create a cycle
        for (Course candidate : candidateSet)
        {
            if (!tracker.checkIfCycle(candidate, current)) {
                // TODO: Abstract all these calls into a single tracker method
                tracker.addToResultNodes(candidate);
                tracker.makeRelationship(candidate, current);
                tracker.removeFromHeads(current);
                tracker.addToHeads(candidate);
                if (!tracker.hasBeenVisited(candidate)) {
                    tracker.addToVisited(candidate);
                    q.add(candidate);
                }
            }
        }

        // Recurse over all candidates, breadth-first
        ResultNode next;
        while ((next = q.poll()) != null) {
            findCoursePathPrivate(next, tracker, config, q);
        }

    }

    private Set<Course> getAllUserCourses(GraphDatabaseService db, ConfigObject config)
            throws Exception
    {
        Set<Course> set = new HashSet<>();

        String userID = config.getUserID();
        if (userID == null) {
            return set;
        }
        String userLabelName = config.getUserLabelName();
        Label userLabel = Label.label(userLabelName);
        String userIDPropName = config.getUserIDPropName();

        // TODO: Ponder this
        // The procedure doesn't fail if a user id is provided, but not user is matched
        // Is this the behavior we really want?
        Node user = db.findNode(userLabel, userIDPropName, userID);
        if (user == null) {
            return set;
        }

        RelationshipType type = RelationshipType.withName(config.getCompletedCourseRelName());

        Iterator<Relationship> completed = user.getRelationships(Direction.OUTGOING, type).iterator();

        if (!completed.hasNext()) {
            return null;
        }

        while(completed.hasNext()) {
            Relationship rel = completed.next();
            set.add(new Course(rel.getOtherNode(user), config, db));
        }

        return set;
    }


    /**
     * Class defining the result of our search
     * Per the neo4j docs,  must have public fields
     */
    public class GraphResult
    {
        public List<Node> nodes;
        public List<Relationship> relationships;

        public GraphResult(List<Node> nodes, List<Relationship> relationships)
        {
            this.nodes = nodes;
            this.relationships = relationships;
        }
    }
}
