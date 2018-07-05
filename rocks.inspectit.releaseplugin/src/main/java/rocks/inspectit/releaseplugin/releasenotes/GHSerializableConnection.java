package rocks.inspectit.releaseplugin.releasenotes;

import static org.jenkinsci.plugins.github.config.GitHubServerConfig.tokenFor;
import static org.jenkinsci.plugins.github.config.GitHubServerConfig.withHost;

import java.io.IOException;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.URL;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.jenkinsci.plugins.github.GitHubPlugin;
import org.jenkinsci.plugins.github.config.GitHubServerConfig;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.HttpConnector;
import org.kohsuke.github.RateLimitHandler;

import com.cloudbees.jenkins.GitHubRepositoryName;
import com.cloudbees.jenkins.GitHubWebHook;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.OkUrlFactory;

import jenkins.model.Jenkins;

public class GHSerializableConnection implements Serializable{

	/**
	 * 
	 */
	private static final long serialVersionUID = -5248990998725615329L;
	
	private String accessToken;
	private String apiUrl;
	
	private String totalRepoName;
	
	
	//both implement serializable, so this should be valid
	private Proxy.Type proxyType;
	private SocketAddress proxySocketAdress;
	
	public GHSerializableConnection(GitHubRepositoryName ghrpn) {

		totalRepoName = String.format("%s/%s", ghrpn.getUserName(), ghrpn.getRepositoryName());
		
		
		//workaround for old 
		//GitHubServerConfig ghsc = from(GitHubPlugin.configuration().getConfigs()).filter(withHost(ghrpn.getHost())).first().orNull();
		//as access to FluentIterableWrapper is not allowed anymore
		GitHubServerConfig ghsc = null;
		List<GitHubServerConfig> allConfigs = GitHubPlugin.configuration().getConfigs();
		for(GitHubServerConfig conf : allConfigs) {
			if(withHost(ghrpn.getHost()).apply(conf)) {
				ghsc = conf;
				break;
			}
		}
		
        accessToken = tokenFor(ghsc.getCredentialsId());
        apiUrl = ghsc.getApiUrl();
        
        //get the proxy configuration
        Jenkins jenkins = GitHubWebHook.getJenkinsInstance();
        if (jenkins.proxy == null) {
        	proxySocketAdress = null; //represents no-proxy
        } else {
            Proxy proxyToUse = jenkins.proxy.createProxy(apiUrl);
            proxyType = proxyToUse.type();
            proxySocketAdress = proxyToUse.address();
        }
        
        
	}
	
	public GitHub connect() {
		 GitHubBuilder builder = new GitHubBuilder()
         .withOAuthToken(accessToken)
         .withConnector(buildConnector())
         .withRateLimitHandler(RateLimitHandler.FAIL);
		 try {
		     if (apiUrl != null && !apiUrl.isEmpty()) {
		         builder.withEndpoint(apiUrl);
		     }
		     return builder.build();
		 } catch (IOException e) {
		     return null;
		 }
	}
	
	public String getTotalRepositoryName() {
		return totalRepoName;
	}
	
	private HttpConnector buildConnector() {
		Proxy proxy = Proxy.NO_PROXY;
		if(proxySocketAdress != null) {
			proxy = new Proxy(proxyType, proxySocketAdress);
		}
		OkHttpClient client = new OkHttpClient().setProxy(proxy);
		client.setConnectTimeout(10, TimeUnit.MINUTES);
		client.setWriteTimeout(30, TimeUnit.MINUTES);
		client.setReadTimeout(30, TimeUnit.MINUTES);


        return new OkHttpConnector(new OkUrlFactory(client));
	}
	
	/**
     * Copy-paste due to class loading issues
     *
     * @see org.kohsuke.github.extras.OkHttpConnector
     */
    private static class OkHttpConnector implements HttpConnector {
        private final OkUrlFactory urlFactory;

        private OkHttpConnector(OkUrlFactory urlFactory) {
            this.urlFactory = urlFactory;
        }

        @Override
        public HttpURLConnection connect(URL url) throws IOException {
            return urlFactory.open(url);
        }
    }

}
