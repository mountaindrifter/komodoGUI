// Code was originally written by developer - https://github.com/zlatinb
// Taken from repository https://github.com/zlatinb/zcash-swing-wallet-ui under an MIT license
package com.vaklinov.zcashui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.lang.Exception;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JOptionPane;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.vaklinov.zcashui.OSUtil.OS_TYPE;
import com.vaklinov.zcashui.ZCashClientCaller.WalletCallException;


public class StartupProgressDialog extends JFrame {


    private static final int POLL_PERIOD = 1500;
    private static final int STARTUP_ERROR_CODE = -28;

    private BorderLayout borderLayout1 = new BorderLayout();
    private JLabel imageLabel = new JLabel();
    private JLabel progressLabel = new JLabel();
    private JPanel southPanel = new JPanel();
    private BorderLayout southPanelLayout = new BorderLayout();
    private JProgressBar progressBar = new JProgressBar();
    private ImageIcon imageIcon;

    private final ZCashClientCaller clientCaller;

    public StartupProgressDialog(ZCashClientCaller clientCaller)
    {
        this.clientCaller = clientCaller;

        URL iconUrl = this.getClass().getClassLoader().getResource("images/komodo-logo-large.png");
        imageIcon = new ImageIcon(iconUrl);
        imageLabel.setIcon(imageIcon);
        imageLabel.setBorder(BorderFactory.createEmptyBorder(16, 16, 0, 16));
        Container contentPane = getContentPane();
        contentPane.setLayout(borderLayout1);
        southPanel.setLayout(southPanelLayout);
        southPanel.setBorder(BorderFactory.createEmptyBorder(0, 16, 16, 16));
        contentPane.add(imageLabel, BorderLayout.NORTH);
		JLabel zcashWalletLabel = new JLabel(
			"<html><span style=\"font-style:italic;font-weight:bold;font-size:25px\">" +
		    "Komodo<span style=\"font-style:italic;font-weight:bold;font-size:15px;vertical-align:super\">" +
		    "\u00AE</span> Wallet</span></html>");
		zcashWalletLabel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
		contentPane.add(zcashWalletLabel, BorderLayout.CENTER);
        contentPane.add(southPanel, BorderLayout.SOUTH);
        progressBar.setIndeterminate(true);
        southPanel.add(progressBar, BorderLayout.NORTH);
        progressLabel.setText("Starting...");
        southPanel.add(progressLabel, BorderLayout.SOUTH);
        setLocationRelativeTo(null);
        pack();

        this.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
    }

    public void waitForStartup() throws Exception,IOException,
        InterruptedException,WalletCallException,InvocationTargetException {

        // special handling of OSX app bundle
//        if (OSUtil.getOSType() == OS_TYPE.MAC_OS) {
//            ProvingKeyFetcher keyFetcher = new ProvingKeyFetcher();
//            keyFetcher.fetchIfMissing(this);
//            if ("true".equalsIgnoreCase(System.getProperty("launching.from.appbundle")))
//                performOSXBundleLaunch();
//        }

        if (OSUtil.getOSType() == OS_TYPE.MAC_OS) {
          ProvingKeyFetcher keyFetcher = new ProvingKeyFetcher();
          keyFetcher.fetchIfMissing(this);
        }
        if(OSUtil.getOSType() == OS_TYPE.WINDOWS){
          ProvingKeyFetcher keyFetcher = new ProvingKeyFetcher();
          keyFetcher.fetchIfMissing(this);
        }

        System.out.println("Splash: checking if komodod is already running...");
        boolean shouldStartZCashd = false;
        try {
            clientCaller.getDaemonRawRuntimeInfo();
        } catch (IOException e) {
        	// Relying on a general exception may be unreliable
        	// may be thrown for an unexpected reason!!! - so message is checked
        	if (e.getMessage() != null &&
        		e.getMessage().toLowerCase(Locale.ROOT).contains("error: couldn't connect to server"))
        	{
        		shouldStartZCashd = true;
        	}
        }

        if (!shouldStartZCashd) {
            System.out.println("Splash: komodod already running...");
            // What if started by hand but taking long to initialize???
//            doDispose();
//            return;
        } else
        {
        	System.out.println("Splash: komodod will be started...");
        }

        final Process daemonProcess =
        	shouldStartZCashd ? clientCaller.startDaemon() : null;

        setProgressText("Waiting for daemon to start...");
		
        Thread.sleep(POLL_PERIOD); // just a little extra

        int iteration = 0;
        while(true) {
        	iteration++;
            Thread.sleep(POLL_PERIOD);

            JsonObject info = null;

            try
            {
            	info = clientCaller.getDaemonRawRuntimeInfo();
            } catch (IOException e)
            {
                setProgressText("Waiting for daemon to start..." + Integer.toString(15 - iteration));
                
                // wait 15 - POLL_PERIOD (1.5sec) intervals before asking user to continue waiting...
            	if (iteration > 15)
                {
                    int dialogButton = JOptionPane.YES_NO_OPTION;
                    int dialogResult = JOptionPane.showConfirmDialog (null, "Daemon taking too long to start, continue waiting?","Warning",dialogButton);
                    if(dialogResult != JOptionPane.YES_OPTION){
                        break;
                    } else {
                        iteration = 0;
                    }
                }
                continue;
            }

            JsonValue code = info.get("code");
            if (code == null || (code.asInt() != STARTUP_ERROR_CODE))
                break;
            final String message = info.getString("message", "???");
            setProgressText(message);

        }

        // doDispose(); - will be called later by the main GUI

        if (daemonProcess != null) // Shutdown only if we started it
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                System.out.println("Stopping komodod because we started it - now it is alive: " +
                		           StartupProgressDialog.this.isAlive(daemonProcess));
                try
                {
                    clientCaller.stopDaemon();
	                long start = System.currentTimeMillis();

	                while (!StartupProgressDialog.this.waitFor(daemonProcess, 3000))
	                {
	                	long end = System.currentTimeMillis();
	                	System.out.println("Waiting for " + ((end - start) / 1000) + " seconds for komodod to exit...");

	                	if (end - start > 10 * 1000)
	                	{
	                		clientCaller.stopDaemon();
	                		daemonProcess.destroy();
	                	}

	                	if (end - start > 1 * 60 * 1000)
	                	{
	                		break;
	                	}
	                }

	                if (StartupProgressDialog.this.isAlive(daemonProcess)) {
	                    	System.out.println("komodod is still alive although we tried to stop it. " +
	                                           "Hopefully it will stop later!");
	                        //System.out.println("zcashd is still alive, killing forcefully");
	                        //daemonProcess.destroyForcibly();
	                    } else
	                        System.out.println("komodod shut down successfully");
                } catch (Exception bad) {
                    System.out.println("Couldn't stop komodod!");
                    bad.printStackTrace();
                }
            }
        });

    }

    public void doDispose() {
        SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				setVisible(false);
				dispose();
			}
		});
    }

    public void setProgressText(final String text) {
        SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				progressLabel.setText(text);
			}
	     });
    }

    // TODO: Unused for now
    private void performOSXBundleLaunch() throws IOException, InterruptedException {
        System.out.println("performing OSX Bundle-specific launch");
        File bundlePath = new File(System.getProperty("zcash.location.dir"));
        bundlePath = bundlePath.getCanonicalFile();

        // run "first-run.sh"
        File firstRun = new File(bundlePath,"first-run.sh");
        Process firstRunProcess = Runtime.getRuntime().exec(firstRun.getCanonicalPath());
        firstRunProcess.waitFor();
    }


    // Custom code - to allow JDK7 compilation.
    public boolean isAlive(Process p)
    {
    	if (p == null)
    	{
    		return false;
    	}

        try
        {
            int val = p.exitValue();

            return false;
        } catch (IllegalThreadStateException itse)
        {
            return true;
        }
    }


    // Custom code - to allow JDK7 compilation.
    public boolean waitFor(Process p, long interval)
    {
		synchronized (this)
		{
			long startWait = System.currentTimeMillis();
			long endWait = startWait;
			do
			{
				boolean ended = !isAlive(p);

				if (ended)
				{
					return true; // End here
				}

				try
				{
					this.wait(100);
				} catch (InterruptedException ie)
				{
					// One of the rare cases where we do nothing
					ie.printStackTrace();
				}

				endWait = System.currentTimeMillis();
			} while ((endWait - startWait) <= interval);
		}

		return false;
    }
}
