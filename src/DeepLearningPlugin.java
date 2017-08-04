/*
 * File: DeepLearningPlugin.java
 * Author: Lake Sain-Thomason
 * Date: 7/10/2017
 * Purpose: Example Plugin that shows how to 
 * 	incorporate python scripts into SeqGeq plugins
 */
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.WindowConstants;

import org.apache.commons.io.FileUtils;

import com.flowjo.lib.parameters.ParameterSelectionPanel;
import com.flowjo.lib.parameters.ParameterSelectionPanel.eParameterSelectionMode;
import com.flowjo.lib.parameters.ParameterSetMgrInterface;
import com.treestar.lib.FJPluginHelper;
import com.treestar.lib.PluginHelper;
import com.treestar.lib.core.ExportFileTypes;
import com.treestar.lib.core.ExternalAlgorithmResults;
import com.treestar.lib.core.PopulationPluginInterface;
import com.treestar.lib.core.SeqGeqExternalAlgorithmResults;
import com.treestar.lib.file.FileUtil;
import com.treestar.lib.gui.GuiFactory;
import com.treestar.lib.gui.numberfields.RangedIntegerTextField;
import com.treestar.lib.gui.panels.FJLabel;
import com.treestar.lib.gui.swing.SwingUtil;
import com.treestar.lib.xml.SElement;

import java.io.InputStream;


public class DeepLearningPlugin implements PopulationPluginInterface {
	private List<String> fParameters = new ArrayList<String>();
	
	//arguments to export to python script
	private String targetPath;
	private String sourcePath;
	private String outputPath;
	private String resultName;
	
	//ImportCSV argument
	private SElement queryElement;
	
	//python script place holder
	private static File gScriptFile = null;
	
	//default min epochs
	private int numEpochs = 5;
	
	//start off with an empty state
	private pluginState state = pluginState.empty; 
	
	// This enum defines the possible states of the plugin node
	public enum pluginState {
		empty, learned, ready
	}

	@Override
	public boolean promptForOptions(SElement fcmlQueryElement, List<String> parameterNames) 
	{	
		//only run this method when the plugin is initialized 
		if (state != pluginState.empty)
			return true;
		
		//obtain the fcmlQueryElement for the ImportCSV method
		queryElement = fcmlQueryElement;
		
		ParameterSetMgrInterface mgr = PluginHelper.getParameterSetMgr(fcmlQueryElement);
		if (mgr == null)
			return false;
		List<Object> guiObjects = new ArrayList<Object>();
		FJLabel explainText = new FJLabel();
		guiObjects.add(explainText);

		explainText = new FJLabel();
		guiObjects.add(explainText);
		String text = "<html><body>";
		text += "Enter the number of Epochs and select the <br>genes you want included in the resultant csv file";
		text += "</body></html>";
		explainText.setText(text);
		// entry
		FJLabel label = new FJLabel("Number of Epochs (5 - 600) ");
		String tip = "A higher number of Epochs will result in more accurately trained data but takes longer.";
		label.setToolTipText(tip);
		RangedIntegerTextField epochInputField = new RangedIntegerTextField(5, 600);
		epochInputField.setInt(numEpochs);
		epochInputField.setToolTipText(tip);
		GuiFactory.setSizes(epochInputField, new Dimension(50, 25));
		Box box = SwingUtil.hbox(Box.createHorizontalGlue(), label, epochInputField, Box.createHorizontalGlue());
		guiObjects.add(box);
		
		ParameterSelectionPanel pane = new ParameterSelectionPanel(mgr,
				eParameterSelectionMode.WithSetsAndParameters, true, false, false, true);
		Dimension dim = new Dimension(300, 500);
		pane.setMaximumSize(dim);
		pane.setMinimumSize(dim);
		pane.setPreferredSize(dim);

		pane.setSelectedParameters(parameterNames);
		parameterNames.clear();

		guiObjects.add(pane);
		
		int option = JOptionPane.showConfirmDialog(null, guiObjects.toArray(), "Deep Learning Plugin",
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE, null);
		
		if (option == JOptionPane.OK_OPTION) 
		{
			
			// user clicked ok, get all selected parameters
			fParameters.addAll(pane.getParameterSelection());
			
			// make sure 'CellId' is included
			if (!fParameters.contains("CellId"))
				fParameters.add("CellId");
			
			// get other GUI inputs
			numEpochs = epochInputField.getInt();
			return true;
		} 
		else
			return false;
	}

	@Override
	public void setElement(SElement element) 
	{
		SElement params = element.getChild("Parameters");
		if (params == null)
			return;
		fParameters.clear();
		for (SElement elem : params.getChildren()) {
			fParameters.add(elem.getString("name"));
		}
 
		numEpochs = element.getInt("numEpochs");
		state = pluginState.valueOf(element.getString("state"));
		if (state == pluginState.learned)
		{
			sourcePath = element.getString("sourcePath");
			resultName = element.getString("resultName");
		}
	}
	
	@Override
	public SElement getElement() 
	{		
		SElement result = new SElement(getName());
		// store the parameters the user selected
		if (!fParameters.isEmpty()) {
			SElement elem = new SElement("Parameters");
			result.addContent(elem);
			for (String pName : fParameters) {
				SElement e = new SElement("P");
				e.setString("name", pName);
				elem.addContent(e);
			}
		}
		result.setInt("numEpochs", numEpochs);
		result.setString("state", state.toString());
		if (state == pluginState.learned)
		{
			result.setString("resultName", resultName.toString());
			result.setString("sourcePath", sourcePath.toString());
		}
		return result;	
	}
	
	/****************************************************************
	* Function: getScriptFile
	* Purpose: Unpacks the python script from the jar file saves
	* 	it to the local files system in the outputFolder
	* Arguments: Path to the output folder defined by SeqGeq
	* Result: Returns the file object of the python script
	****************************************************************/
	private File getScriptFile(File absolutePath) {
		if(gScriptFile == null) 
		{
		    InputStream findScriptPath = this.getClass().getClassLoader().getResourceAsStream("python/train_MMD_ResNet.py");
		    if(findScriptPath != null) 
		    {
		    	try 
		    	{
	            File scriptFile = new File(absolutePath, "train_MMD_ResNet.py");
	            FileUtil.copyStreamToFile(findScriptPath, scriptFile);
	            gScriptFile = scriptFile;
		    	} 
		    	catch (Exception exception) 
		    	{
		    		System.out.println("Script not found");
		    	}
	        System.out.println("Script found");
	    	}
		}	
		return gScriptFile;
	}


	
	/****************************************************************
	* Function: executePython
	* Purpose: Executes the Deep Learning python script and prints
	* the results
	* Arguments: None
	* Result: Returns true if everything went as expected, false if
	* any exceptions occur
	****************************************************************/
	public boolean executePython(File outputFolder)
	{
		try 
		{	
			File myPythonFile = getScriptFile(outputFolder);
			System.out.println("Trying to execute python script....\n");			
			
			if (myPythonFile != null)
			{
				String execLine =   
						"python" + " "
						 + myPythonFile.getAbsolutePath() + " "
						+ numEpochs + " "
						+ sourcePath + " "
						+ targetPath + " "
						+ outputFolder + " "
						+ resultName + " ";
							
				Process proc = Runtime.getRuntime().exec(execLine);
				System.out.println("Working.....");
				
				//prepare to deliver the output from the python file
				OutputStream stdout = proc.getOutputStream();
				InputStream stdin = proc.getInputStream();
				InputStream stderr = proc.getErrorStream();
				InputStreamReader isrIn = new InputStreamReader(stdin);
				InputStreamReader isr = new InputStreamReader(stderr);
				BufferedReader br = new BufferedReader(isrIn);
				
	            Thread.sleep(1000);

	            //deliver the output from the python file
	            String line = null;
	            while ((line = br.readLine()) != null) {
	                System.out.println(line);
	            }
	            //wait for the process to finish up
				proc.waitFor();
				
				System.out.println("Execution successful!\n");
				
				return true;
			}
		}
		catch (InterruptedException e) 
		{
			e.printStackTrace();
			return false;
		}
		catch (IOException e)
		{
			e.printStackTrace();
			return false;
		} 
		return false;
	}
	@Override
	public Icon getIcon() {
		return null;
	}

	@Override
	public String getName() {
		return "Deep_Learning_Plugin";
	}

	@Override
	public List<String> getParameters() {
		if (!fParameters.contains("CellId"))
			fParameters.add("CellId");
		return fParameters;
	}

	@Override
	public String getVersion() {
		return "1.0";
	}

	/**
	 * Invokes the algorithm and returns the results.
	 */
	@Override
	public ExternalAlgorithmResults invokeAlgorithm(SElement fcmlElem, File sampleFile, File outputFolder) {
		//ExternalAlgorithmResults results = new ExternalAlgorithmResults();
		SeqGeqExternalAlgorithmResults results = new SeqGeqExternalAlgorithmResults();
		//initial plugin call
		if (state == pluginState.empty)
		{
			sourcePath = sampleFile.getAbsolutePath();
			String fileName = sampleFile.getName().replace(' ', '_');
			resultName = fileName.replace(".csv..ExtNode.csv", "");
			state = pluginState.learned;
		}
		//second call
		else if (state == pluginState.learned)
		{
			targetPath = sampleFile.getAbsolutePath();
			//the ready state prevents users from continuing to use the plugin outside 
			// of it's intended use
			state = pluginState.ready;
			outputPath = outputFolder.getAbsolutePath();
			
			if (executePython(outputFolder)) {		
				//Import the resultant CSV file into the current workspace
				FJPluginHelper.loadSamplesIntoWorkspace(fcmlElem, new String[]{
						outputFolder + "/" + resultName + "DL.csv",});
			}
		}
		return results;
	}

	@Override
	public ExportFileTypes useExportFileType() {
		return ExportFileTypes.CSV_SCALE;
	}
}
