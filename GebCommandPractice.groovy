import java.util.List;
import java.util.concurrent.TimeUnit

import org.codehaus.groovy.control.CompilerConfiguration

import com.opera.core.systems.OperaDriver

import geb.Browser
import geb.navigator.NonEmptyNavigator
import geb.report.Base64
import geb.report.PngUtils
import geb.report.ExceptionToPngConverter
import geb.waiting.WaitTimeoutException

import org.openqa.selenium.OutputType
import org.openqa.selenium.Proxy
import org.openqa.selenium.TakesScreenshot
import org.openqa.selenium.WebDriver
import org.openqa.selenium.WebDriverException
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.ie.InternetExplorerDriver
import org.openqa.selenium.safari.SafariDriver
import org.openqa.selenium.remote.CapabilityType
import org.openqa.selenium.remote.DesiredCapabilities

import org.apache.commons.lang3.StringUtils

@Grapes([
		@Grab(group='net.lightbody.bmp', module='browsermob-proxy', version='2.0-beta-8'),
		@Grab(group='org.gebish', module='geb-core', version='[0.9.2,)'),
		@Grab(group='org.seleniumhq.selenium', module='selenium-firefox-driver', version='[2.37.1,)'),
		@Grab(group='org.seleniumhq.selenium', module='selenium-firefox-driver', version='[2.37.1,)'),
		@Grab(group='org.seleniumhq.selenium', module='selenium-server', version='[2.37.1,)'),
		@Grab(group='org.seleniumhq.selenium', module='selenium-chrome-driver', version='[2.37.1,)'),
		@Grab(group='org.seleniumhq.selenium', module='selenium-ie-driver', version='[2.37.1,)'),
		@Grab(group='org.seleniumhq.selenium', module='selenium-api', version='[2.37.1,)'),
		@Grab(group='org.apache.commons', module='commons-lang3', version='3.1'),
		@Grab(group='com.opera', module='operadriver', version='[1.5,)'),
		@Grab(group='commons-cli', module='commons-cli', version='1.1')
])
@GrabExclude('org.bouncycastle:bcprov')

class GebCommandPractice {
	
	def static final String INITIAL_PROMPT = "What page would you like to go to (please enter a full url including http / https)?"
	def static final String DEFAULT_PROMPT = "Please enter a selector, browser command or method on a navigator to execute:"
	
	def static WebDriver driver = null
	def static Browser browser = null

	//Update this array if new browsers are added
	def static supportedBrowsers = ["ie", "safari", "chrome", "firefox", "opera"]

	//This is used to allow for the dynamic selector to be passed in to the browser we create
	def static compilerConfiguration = new CompilerConfiguration()
	def static shell = null
	
	static {
		compilerConfiguration.scriptBaseClass = DelegatingScript.class.name
		shell = new GroovyShell(GebCommandPractice.class.classLoader, new Binding(), compilerConfiguration)
	}

	//Command that is retrieved from the user input prompt
	def static String command
	
	//Previous command that the user input (for chaining together calls on previous items), was noticing a call to find was not working properly without the full command
	def static String previousCommand
	
	//Browser the user wishes to run against
	def static String selectedBrowser
	
	//The Navigator item which is currently active, by active we mean it was the last item returned from the browser which is a NonEmptyNavigator
	def static currentlySelected = null
	
	//Simple helper to show the user the options for running this program
	static parseOptions(args) {

		def cli = new CliBuilder(
			header: '\nAvailable options (use -h for help):\n'
		)
		cli.with {
			h(longOpt: 'help', 'Usage Information', required: false)
			b(longOpt: 'browser', "Browser - select from one of the following: ${supportedBrowsers}", args: 1, required: true)
		}
		def opt = cli.parse(args)
		
		if (!opt) { 
			System.exit(1)
		}
		
		if (opt.h || opt.help) {
			cli.usage()
			System.exit(0)
		}
		selectedBrowser = opt.b ?: opt.browser
	}

	static main(args) {
		parseOptions(args)
		
		println "Creating driver"
		driver = createDriver(selectedBrowser)
		
		if (!driver) {
			println "The browser was unable to be created successfully."
			return
		}
		
		println "driver creation completed"
		
		command = getCommandValue(INITIAL_PROMPT)
		
		if (StringUtils.equals(command, "quit()")) {
			browser?.quit()
			driver?.quit()
			return
		}

		while (!StringUtils.startsWith(command, "http")) {
			command = getCommandValue(INITIAL_PROMPT)
		}
		
		println "${command} received"
		
		driver.get(command) //This is in case we wish to support android, it does not handle going to a url before it has been somewhere...
		browser = Browser.drive(driver: driver) {}
		
		def commandReturn = null
		
		while (driver != null) {
			try {
				//Parse the command passed in
				previousCommand = command
				command = getCommandValue(DEFAULT_PROMPT) 
				println "Command received: ${command}"
				if (StringUtils.startsWith(command, '$') || StringUtils.equals(command, "quit()") || currentlySelected == null) {
					commandReturn = runCommandAgainstBrowser(command)
				} else {
					commandReturn = runCommandAgainstCurrentlySelectedItem(command)
				}
				println "Command Return: ${commandReturn}"
				updateCurrentlySelected(commandReturn)
			} catch (Exception e) {
				println "${command} was invalid."
			}
		}
	}
	
	// Helper method to add borders to newly selected item(s), and clear from previously selected item(s)
	// This method will fail if any interaction occurs which causes the page to re-render. I'm okay with that
	def static updateCurrentlySelected(commandReturn) {
		if (commandReturn instanceof NonEmptyNavigator) {
			if (currentlySelected) {
				println "Clearing border on currently selected item(s)"
				currentlySelected.allElements().each { element ->
					browser.js.exec(element, 'border', 'none', "jQuery(arguments[0]).css(arguments[1], arguments[2]);")
				}
			}
			println "Attempting to add border to selected element(s)"
			currentlySelected = commandReturn
			currentlySelected.allElements().each { element ->
				browser.js.exec(element, 'border', '4px solid red', "jQuery(arguments[0]).css(arguments[1], arguments[2]);")
			}
			
		}
	}
	
	// Helper method which will attempt to run against the last selected item
	// This will not work in all instances, but the trade off is smaller / easier code.
	// Since this is a simple POC, and we display the command we generated, I was okay with this.
	def static runCommandAgainstCurrentlySelectedItem(String currentItemCommand) {
		println "Running against currently selected item"
		def script = shell.parse(previousCommand + currentItemCommand)
		command = previousCommand + currentItemCommand
		script.setDelegate(browser)
		return script.run()
	}
	
	//Helper method to run this against the real browser
	def static runCommandAgainstBrowser(String browserCommand) {
		println "Running command against browser"
		if (StringUtils.equals(browserCommand, "quit()")) {
			println "Quitting browser"
			browser.quit()	
			driver?.quit();
			driver = null
			return
		}
		def script = shell.parse(browserCommand)
		script.setDelegate(browser)
		return script.run()
	}
	
	//Helper method to display a swing input dialog which will drive the browser
	def static String getCommandValue(String prompt) {
		prompt += "\nType quit() to exit the browser"
		def inputCommand = null
		def commandReader = javax.swing.JOptionPane.&showInputDialog
		inputCommand = StringUtils.trimToNull(commandReader(prompt, previousCommand))
		if (inputCommand == null) {
			println "Got null response"
			inputCommand = commandReader prompt
		}
		inputCommand
	}

	//Helper method to create the driver we will utilize for the test run
	def static createDriver(String selectedBrowser) {
		WebDriver driver
		switch (selectedBrowser) {
			case "ie":
				driver = new InternetExplorerDriver()
			break
			
			case "firefox":
				driver = new FirefoxDriver()
			break
			
			case "chrome":
				driver = new ChromeDriver()
			break
			
			case "opera":
				//driver = new OperaDriver()
				driver = null //Opera has not updated to allow for testing with the latest version of their browser yet
			break
				
			case "safari":
				driver = new SafariDriver()
			break
				
			default:
				println "This browser needs to be setup"	
			break
		}
		driver
	}
}
