package rocks.inspectit.releaseplugin.ticketing;

import hudson.EnvVars;
import hudson.Extension;
import hudson.RelativePath;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.ComboBoxModel;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang.text.StrSubstitutor;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import rocks.inspectit.releaseplugin.IssueUpdateBuilder;
import rocks.inspectit.releaseplugin.JIRAAccessTool;
import rocks.inspectit.releaseplugin.JIRAAccessTool.BuildingLambda;
import rocks.inspectit.releaseplugin.JIRAMetadataCache;

import com.atlassian.jira.rest.client.api.domain.BasicPriority;
import com.atlassian.jira.rest.client.api.domain.Issue;
import com.atlassian.jira.rest.client.api.domain.IssueType;
import com.atlassian.jira.rest.client.api.domain.input.ComplexIssueInputFieldValue;
import com.atlassian.jira.rest.client.api.domain.input.IssueInputBuilder;

/**
 * 
 * Form for entering the data of a new Ticket to create.
 * 
 * @author Jonas Kunz
 *
 */
public class AddTicketTemplate extends AbstractDescribableImpl<AddTicketTemplate> {
	
	/**
	 * if true, tickets will only be published if they don't already exist.
	 */
	private boolean performDuplicateCheck;
	
	/**
	 * JQL selecting the parent ticket in case of the newly created ticket is a subticket.
	 */
	private String parentJQL;
	
	/**
	 * the title of the new ticket.
	 */
	private String title;
	
	/**
	 * The name of the type of the new ticket.
	 */
	private String type;
	/**
	 * The priority of this new ticket.
	 */
	private String priority;
	/**
	 * The long description text of the ticket.
	 */
	private String description;	
	

	/**
	 * If non-null / empty, the ticket key of the generated ticket will be stored in this environment variable.
	 */
	private String envVarName;	

	private List<AddTicketField> fieldValues;
	
	

	/**
	 * Databound constructor, called by jenkins.
	 * @param performDuplicateCheck value of performDuplicateCheck
	 * @param parentJQL the jql fitler selecting the parent
	 * @param title the title
	 * @param type the type
	 * @param affectedVersion the affected version
	 * @param priority the priority
	 * @param description the description
	 */
	@DataBoundConstructor
	public AddTicketTemplate(boolean performDuplicateCheck, String parentJQL, String title, String type,
			String priority, String description,String envVarName, List<AddTicketField> fieldValues) {
		super();
		this.performDuplicateCheck = performDuplicateCheck;
		this.parentJQL = parentJQL;
		this.title = title;
		this.type = type;
		this.priority = priority;
		this.description = description;
		this.envVarName = envVarName;
		this.fieldValues = fieldValues == null ? new ArrayList<AddTicketField>() : fieldValues;
	}

	public String getTitle() {
		return title;
	}
	
	public String getType() {
		return type;
	}
	
	public String getPriority() {
		return priority;
	}

	public String getDescription() {
		return description;
	}
	

	public boolean getPerformDuplicateCheck() {
		return performDuplicateCheck;
	}

	public String getParentJQL() {
		return parentJQL;
	}
	
	
	public String getEnvVarName() {
		return envVarName;
	}

	public List<AddTicketField> getFieldValues() {
		return fieldValues;
	}

	/**
	 * Publishes this TIcket on JIRA, if it does not yet exist.
	 * A ticket is considered to be already existing, when a ticket with matching version and title already exists.
	 *  
	 * @param jira
	 * 		the tool to access jira
	 * @param varReplacer
	 * 		the variables to be replaced
	 * @param logger
	 * 		log printstream
	 */
	public void publishTicket(final JIRAAccessTool jira, final StrSubstitutor varReplacer, final PrintStream logger, EnvVars vars) {
		final String title = varReplacer.replace(this.title);
		String type = varReplacer.replace(this.type);
		String priority = varReplacer.replace(this.priority);
		final String description = varReplacer.replace(this.description);
		String parentJQL = varReplacer.replace(this.parentJQL);

		IssueType issueType = jira.getIssueTypeByName(type);
		final BasicPriority issuePriority = jira.getIssuePriorityByName(priority);
		String parentKey = null;
		
		if (issueType.isSubtask()) {
			List<Issue> result = jira.getTicketsByJQL(parentJQL);
			if (result.size() != 1) {
				throw new RuntimeException("Invalid number of tickets (" + result.size()
						+ ") matching parent JQL '" + parentJQL + "'");
			}
			parentKey = result.get(0).getKey();
		}
		
		final String finalParentKey = parentKey;
		
		if (performDuplicateCheck) {
			String jql = "summary ~ \"" + title + "\"";
			
			//make sure to only consider tickets with the same parent if it is an subticket 
			if (finalParentKey != null) {
				jql += " AND parent = " + finalParentKey;
			}
			
			/*
			boolean alreadyPresent = 
			jira.getTicketsByJQL(jql)
			.stream().filter(i -> i.getSummary().equalsIgnoreCase(title))
			.count() > 0;
			*/
			boolean alreadyPresent = false;
			for (Issue is : jira.getTicketsByJQL(jql)) {
				if (is.getSummary().equalsIgnoreCase(title)) {
					alreadyPresent = true;
					break;
				}
			}
			
			if (alreadyPresent) {
				logger.println("Skipping Ticket \"" + title + "\", as it is already present.");
				return;
			}
		}
		

		logger.println("Creating Ticket \"" + title + "\".");
		
		
		final String ticketKey = jira.addTicket(new BuildingLambda<IssueInputBuilder>() {
			
			@Override
			public void build(IssueInputBuilder b) {
				b.setSummary(title);
				b.setDescription(description);
				if (issuePriority != null) {
					b.setPriority(issuePriority);
				}
				if (finalParentKey != null) {
					b.setFieldValue("parent", ComplexIssueInputFieldValue.with("key", finalParentKey));
					
				}
			}
		}, issueType).getKey();
		
		if(!fieldValues.isEmpty()) {
			jira.updateTicket(ticketKey, new BuildingLambda<IssueUpdateBuilder>() {

				@Override
				public void build(IssueUpdateBuilder builder) {
					for(AddTicketField addOp : fieldValues) {
						addOp.apply(builder, jira, varReplacer, logger);
					}
				}
			});
		}
		
		String realEnvVar = varReplacer.replace(envVarName == null? ""  : envVarName);
		if(!realEnvVar.isEmpty()) {
			logger.print("Setting var "+realEnvVar);
			vars.put(realEnvVar, ticketKey);
		}
		
		
	}


	/**
	 * Descriptor class.
	 * @author JKU
	 *
	 */
	@Extension public static class DescriptorImpl extends Descriptor<AddTicketTemplate> {
        @Override public String getDisplayName() {
            return "New JIRA Ticket Template";
        }
        
        /**
         * Combobox populating method.
         * @param jiraCredentialsID the credentials used for access.
         * @return a ComboBoxModel containing the JIRA values as suggestions
         */
        public ComboBoxModel doFillTypeItems(@RelativePath("..") @QueryParameter String jiraCredentialsID) { 	
        	ComboBoxModel result = new ComboBoxModel();
        	result.addAll(JIRAMetadataCache.getSingleton().getAvailableIssueTypes(jiraCredentialsID));
    		return result;
        }

        /**
         * Combobox populating method.
         * @param jiraCredentialsID the credentials used for access.
         * @return a ComboBoxModel containing the JIRA values as suggestions
         */
        public ComboBoxModel doFillPriorityItems(@RelativePath("..") @QueryParameter String jiraCredentialsID) { 	
        	ComboBoxModel result = new ComboBoxModel();
        	result.addAll(JIRAMetadataCache.getSingleton().getAvailableIssuePriorities(jiraCredentialsID));
    		return result;
        }

    }
	
	
}
