package rocks.inspectit.releaseplugin;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.MetadataRestClient;
import com.atlassian.jira.rest.client.api.VersionRestClient;
import com.atlassian.jira.rest.client.api.domain.BasicIssue;
import com.atlassian.jira.rest.client.api.domain.BasicPriority;
import com.atlassian.jira.rest.client.api.domain.Field;
import com.atlassian.jira.rest.client.api.domain.Issue;
import com.atlassian.jira.rest.client.api.domain.IssueType;
import com.atlassian.jira.rest.client.api.domain.Priority;
import com.atlassian.jira.rest.client.api.domain.Project;
import com.atlassian.jira.rest.client.api.domain.SearchResult;
import com.atlassian.jira.rest.client.api.domain.Status;
import com.atlassian.jira.rest.client.api.domain.Transition;
import com.atlassian.jira.rest.client.api.domain.Version;
import com.atlassian.jira.rest.client.api.domain.input.IssueInputBuilder;
import com.atlassian.jira.rest.client.api.domain.input.TransitionInput;
import com.atlassian.jira.rest.client.api.domain.input.VersionInputBuilder;
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory;


/**
 * 
 * Tool encapsulating the access to a JIRA Server using the REST-API.
 * 
 * @author Jonas Kunz
 *
 */
public class JIRAAccessTool {
	
	
	/**
	 * The JIRA API client.
	 */
	private JiraRestClient restClient;
	
	/**
	 * The credentials id this connection was created with.
	 */
	String jenkinsCredentialsId;
	
	/**
	 * The url of JIRA.
	 */
	private String url;
	/**
	 * The username used for access.
	 */
	private String user;
	/**
	 * The password used for access.
	 */
	private String password;
	
	/**
	 * The proxy to use.
	 */
	private String proxy;
	
	/**
	 * The key of the project on which this tool operates.
	 */
	private String projectKey;
	
	/**
	 * As some features are not supported by the java rest wrapper for jira, we use an additional plain http client for these features.
	 */
	private JsonHTTPClientWrapper jsonClient;
	
	/**
	 * 
	 * Lambda used for building stuff (like tickets or version).
	 * This takes the overhead of creating a builder away from the caller, as the builder sometimes requires meta-information which might not be known for the caller.
	 * 
	 * @author Jonas Kunz
	 * 
	 * @param <B> the type of the builder which will be supplied.
	 *
	 */
	//@FunctionalInterface
	public interface BuildingLambda<B> {
		/**
		 * The method should pass the information for the new ticket to the IssueInputBuidler given as parameter.
		 * @param builder
		 * 		the issue builder used to build the ticket
		 */
		void build(B builder);
	}
	
	
	
	
	/**
	 * Establishes an connection to JIRA using the given credentials.
	 * 
	 * @param url the url used for access
	 * @param user the username used for access
	 * @param password the password used for access
	 * @param proxy the proxy to use (can be null)
	 * @param projectKey the key of the project
	 * @param jenkinsCredentialsId the id of the jenkins crednetials used for this jira
	 */
	public JIRAAccessTool(String url, String user, String password, String proxy, String projectKey, String jenkinsCredentialsId) {
		this.url = url;
		this.user = user;
		this.password = password;
		this.proxy = proxy;
		this.projectKey = projectKey;
		this.jenkinsCredentialsId = jenkinsCredentialsId;
		connect();
	}
	
	public String getJenkinsCredentialsId() {
		return jenkinsCredentialsId;
	}

	/**
	 * @return A list of the names of all Issue types.
	 */
	public List<String> getAvailableIssueTypes() {
		List<String> names = new ArrayList<String>();
		for (IssueType type : restClient.getMetadataClient().getIssueTypes().claim()) {
			names.add(type.getName());
		}
		return names;
		
	}
	
	/**
	 * @return A List of all names of the available Issue priorities
	 */
	public List<String> getAvailableIssuePriorities() {
		List<String> names = new ArrayList<String>();
		for (Priority priority : restClient.getMetadataClient().getPriorities().claim()) {
			names.add(priority.getName());
		}
		return names;
		
	}
	

	/**
	 * @return A List of the names of all available issue statuses.
	 */
	public List<String> getAvailableIssueStatuses() {
		List<String> names = new ArrayList<String>();
		for(Status status : restClient.getMetadataClient().getStatuses().claim()) {
			names.add(status.getName());
		}
		return names;
	}
	
	/**
	 * @return A List of the names of all existing versions.
	 */
	public List<String> getAvailableVersions() {
		List<String> names = new ArrayList<String>();
		Project project = restClient.getProjectClient().getProject(projectKey).claim();
		for (Version version : project.getVersions()) {
			names.add(version.getName());
		}
		return names;
		
	}



	/**
	 * private method for initiating the connection.
	 */
	private void connect() {
		
		jsonClient = new JsonHTTPClientWrapper(url, user, password, proxy);
		
		final AsynchronousJiraRestClientFactory factory = new AsynchronousJiraRestClientFactory();
		//TODO: create rest client with proxy
		
		restClient = factory.createWithBasicHttpAuthentication(URI.create(url), user, password);
		//restClient = factory.create(URI.create(url), jsonClient.getHttpClient());
		
	}
	
		
	/**
	 * Marks the given version as released.
	 * If the version does not exist yet, a new version if the given name is created and then marked as released.
	 * @param versionName
	 * 		the name of the version
	 * @param buildingLambda
	 * 		buildingLambda the lambda providing the information about the new version
	 * @return
	 * 		The released version.
	 */
	public Version createUpdateVersion(String versionName, BuildingLambda<VersionInputBuilder> buildingLambda) {
		
		VersionRestClient cl = restClient.getVersionRestClient();
		
		Version existingVersion = getVersionByName(versionName);

		//cannot upload released versions directly, we first have to upload the version and then update it
		if (existingVersion == null) {
			VersionInputBuilder builder = new VersionInputBuilder(projectKey);
			builder.setName(versionName);
			existingVersion = cl.createVersion(builder.build()).claim();
		}
		
		VersionInputBuilder versBuilder = new VersionInputBuilder(projectKey);
		versBuilder.setArchived(existingVersion.isArchived());
		versBuilder.setName(versionName);
		versBuilder.setReleased(existingVersion.isReleased());

		buildingLambda.build(versBuilder);
		
		return cl.updateVersion(existingVersion.getSelf(), versBuilder.build()).claim();
	}
	
	
	

	/**
	 * Returns a Version-object corresponding the version on JIRA with the given name.
	 * @param versionName
	 * 		the name of the version to look for (e.g. 1.0)
	 * @return
	 * 		an instance representing the given version or null if no such version exists.
	 */
	public Version getVersionByName(String versionName) {
		Project project = restClient.getProjectClient().getProject(projectKey).claim();
		Version existingVersion = null;
		for (Version vers : project.getVersions()) {
			if (vers.getName().equalsIgnoreCase(versionName)) {
				existingVersion = vers;
			}
		}
		return existingVersion;
	}
	
	/**
	 * Finds all Tickets which have the given version as their affected version.
	 * Limits the search to the project this connector was constructed with.
	 * 
	 * @param version
	 * 		the version to filter for
	 * @return
	 * 		a list of all issues with this version as affected version.
	 */
	public List<Issue> getTicketsByVersion(Version version) {
		
		return getTicketsByJQL("affectedVersion = " + version.getName());

	}
	
	/**
	 * Finds all Tickets matching the given JQL Query.
	 * Limits the search to the project this tool was constructed with.
	 * @param jqlQuery
	 * 		the query to filter for
	 * @return
	 * 		a list of all issues with this version as affected version.
	 */
	public List<Issue> getTicketsByJQL(String jqlQuery) {
		SearchResult result = restClient.getSearchClient().searchJql("(" + jqlQuery + ") AND project = \"" + projectKey + "\"").claim();
		
		ArrayList<Issue> issues = new ArrayList<Issue>();
		for (Issue is : result.getIssues()) {
			issues.add(is);
		}
		
		return issues;

	}
	
	
	/**
	 * Builds a html page listing all the given Tickets (including links to JIRA).
	 * @param issuesToShow
	 * 		the issues to list on the html page
	 * @return
	 * 		a String containing the html code
	 */
	public String buildReleaseNotesHTML(List<Issue> issuesToShow) {
		
		Set<String> issueTypesSet = new HashSet<String>();
		for (Issue is : issuesToShow) {
			issueTypesSet.add(is.getIssueType().getName());
		}
		ArrayList<String> sortedIssueTypes = new ArrayList<String>(issueTypesSet);
		sortedIssueTypes.sort(String.CASE_INSENSITIVE_ORDER);
		
		String resultHtml = "";
		//build a header and list for each issue type
		for (String issueType : sortedIssueTypes) {
			resultHtml += "<h2>" + issueType + "</h2>";
			resultHtml += "<ul>";
			for (Issue is : issuesToShow) {
				if (is.getIssueType().getName().equals(issueType)) {
					
					resultHtml += "<li>[<a href='" + url + "/browse/" + is.getKey() + "'>" + is.getKey() + "</a>] - " + is.getSummary() + "</li>";
				}
			}
			resultHtml += "</ul>";
		}
		
		return resultHtml;
	}
	
	/**
	 * Publishes a new ticket on JIRA.
	 * 
	 * @param buildingFunction
	 * 		the lambda providing the information of the new ticket.
	 * @param type
	 * 		the issue type (e.g. Task, bug, etc)
	 * @return the created issue
	 */
	public BasicIssue addTicket(BuildingLambda<IssueInputBuilder> buildingFunction, IssueType type) {

		Project project = restClient.getProjectClient().getProject(projectKey).claim();
		IssueInputBuilder builder = new IssueInputBuilder(project, type);
		
		buildingFunction.build(builder);
		return restClient.getIssueClient().createIssue(builder.build()).claim();
	}
	
	/**
	 * Performs an update on the given ticket.
	 * @param ticketKey the id of the ticket to update
	 * 	
	 * @param updatingFunction the lambda for building the update
	 */
	public void updateTicket(String ticketKey, BuildingLambda<IssueUpdateBuilder> updatingFunction) {
		IssueUpdateBuilder builder = new IssueUpdateBuilder();
		updatingFunction.build(builder);
		jsonClient.putJson("/rest/api/2/issue/" + ticketKey, builder.getRequestData());
	}

	/**
	 * Scans for an issue type on JIRA with the given name. 
	 * @param typeName
	 * 		the name of the type to scan for, e.g. task, bug, etc
	 * @return
	 * 		an IssueType instance representing the given issue type.
	 */
	public IssueType getIssueTypeByName(String typeName) {
		MetadataRestClient metadata = restClient.getMetadataClient();
		IssueType type = null;
		for (IssueType type2 : metadata.getIssueTypes().claim()) {
			if (type2.getName().equalsIgnoreCase(typeName)) {
				type = type2;
			}
		}
		return type;
	}
	
	/**
	 * Scans for an issue priority on JIRA with the given name. 
	 * @param priorityName
	 * 		the name of the priority to scan for, e.g. high, medium, etc
	 * @return
	 * 		an BasicPriority instance representing the given issue priority.
	 */
	public BasicPriority getIssuePriorityByName(String priorityName) {
		MetadataRestClient metadata = restClient.getMetadataClient();
		BasicPriority prio = null;
		for (BasicPriority prio2 : metadata.getPriorities().claim()) {
			if (prio2.getName().equalsIgnoreCase(priorityName)) {
				prio = prio2;
			}
		}
		return prio;
	}
	
	/**
	 * Closes the connection.
	 */
	public void destroy() {
		try {
			restClient.close();
			jsonClient.destroy();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}



	/**
	 * Fetches an Issue object using the key of the issue.
	 * 
	 * @param ticketKey
	 * 		the key of the issue to fetch.
	 * @return
	 * 		the corresponding ticket.
	 */
	public Issue getTicketByKey(String ticketKey) {
		return restClient.getIssueClient().getIssue(ticketKey).claim();
	}

	/**
	 * Lists all transitions available from the view of the user for the given ticket. 
	 * @param issue
	 * 		the issue whose transitions shall be fetched.
	 * @return
	 * 		a list of possible transitions.
	 */
	public Iterable<Transition> getAvailableTransitions(Issue issue) {
		return restClient.getIssueClient().getTransitions(issue).claim();
	}
	
	
	/**
	 * Performs a transition on the given ticket.
	 * @param issue
	 * 		the issue on which the transition shall be performed.
	 * @param transition
	 * 		the transition to be performed
	 */
	public void performTransition(Issue issue, TransitionInput transition) {
		restClient.getIssueClient().transition(issue, transition).claim();
	}

	/**
	 * Receives all available Fields.
	 * 
	 * @return a List of the field metadata.
	 */
	public List<FieldMetadata> getAvailableFields() {
		List<FieldMetadata> result = new ArrayList<FieldMetadata>();
		for (Field f : restClient.getMetadataClient().getFields().claim()) {
			
			result.add(new FieldMetadata(f));
		}
		return result;
	}
	
}
