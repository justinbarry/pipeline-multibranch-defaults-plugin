/*
 * The MIT License
 *
 * Copyright (c) 2016 Saponenko Denis
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.pipeline.multibranch.defaults;

import hudson.Extension;
import hudson.Functions;
import hudson.model.*;
import hudson.scm.SCM;
import jenkins.branch.Branch;
import jenkins.model.Jenkins;
import jenkins.scm.api.*;
import org.jenkinsci.lib.configprovider.ConfigProvider;
import jenkins.scm.api.SCMFileSystem;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMRevisionAction;
import jenkins.scm.api.SCMSource;

import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.plugins.configfiles.ConfigFileStore;
import org.jenkinsci.plugins.configfiles.GlobalConfigFiles;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDefinitionDescriptor;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowBranchProjectFactory;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

import java.io.IOException;
import java.util.List;



/**
 * Checks out the local default version of {@link WorkflowBranchProjectFactory#SCRIPT} in order if exist:
 * 1. From module checkout
 * 1. From task workspace directory
 * 2. From global jenkins managed files
 */
class DefaultsBinder extends FlowDefinition {

    @Override public FlowExecution create(FlowExecutionOwner handle, TaskListener listener, List<? extends Action> actions) throws Exception {
        Queue.Executable exec = handle.getExecutable();
        if (!(exec instanceof WorkflowRun)) {
            throw new IllegalStateException("inappropriate context");
        }
        WorkflowRun build = (WorkflowRun) exec;
        WorkflowJob job = build.getParent();
        BranchJobProperty property = job.getProperty(BranchJobProperty.class);
        if (property == null) {
            throw new IllegalStateException("inappropriate context");
        }
        Branch branch = property.getBranch();
        ItemGroup<?> parent = job.getParent();
        if (!(parent instanceof WorkflowMultiBranchProject)) {
            throw new IllegalStateException("inappropriate context");
        }
        SCMSource scmSource = ((WorkflowMultiBranchProject) parent).getSCMSource(branch.getSourceId());
        if (scmSource == null) {
            throw new IllegalStateException(branch.getSourceId() + " not found");
        }
        SCMHead head = branch.getHead();
        SCMRevision tip = scmSource.fetch(head, listener);
        SCM scm;
        if (tip != null) {
            build.addAction(new SCMRevisionAction(tip));
            SCMRevision rev = scmSource.getTrustedRevision(tip, listener);
            try (SCMFileSystem fs = SCMFileSystem.of(scmSource, head, rev)) {
                if (fs != null) { // JENKINS-33273
                    String script = null;
                    try {
                        script = fs.child(PipelineBranchDefaultsProjectFactory.SCRIPT).contentAsString();
                        listener.getLogger().println("Obtained " + PipelineBranchDefaultsProjectFactory.SCRIPT + " from " + rev);
                    } catch (IOException | InterruptedException x) {
                        listener.error("Could not do lightweight checkout, falling back to heavyweight").println(Functions.printThrowable(x).trim());
                    }

                    if (script == null) {
                        script = getGlobalScript(handle);
                    }

                    if (script != null) {
                        return new CpsFlowDefinition(script, true).create(handle, listener, actions);
                    }
                }
            }
            scm = scmSource.build(head, rev);
        } else {
            listener.error("Could not determine exact tip revision of " + branch.getName() + "; falling back to nondeterministic checkout");
            // Build might fail later anyway, but reason should become clear: for example, branch was deleted before indexing could run.
            scm = branch.getScm();
        }
        return new CpsScmFlowDefinition(scm, PipelineBranchDefaultsProjectFactory.SCRIPT).create(handle, listener, actions);
    }


    public String getGlobalScript(FlowExecutionOwner handle) throws Exception {
        Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) {
            throw new IllegalStateException("inappropriate context");
        }
        Queue.Executable exec = handle.getExecutable();
        if (!(exec instanceof WorkflowRun)) {
            throw new IllegalStateException("inappropriate context");
        }

        ConfigFileStore store = GlobalConfigFiles.get();
        if (store != null) {
            Config config = store.getById(PipelineBranchDefaultsProjectFactory.SCRIPT);
            if (config != null) {
                return config.content;
            }
        }
        throw new IllegalArgumentException("Default " + PipelineBranchDefaultsProjectFactory.SCRIPT + " not found. Check configuration.");
    }

    @Extension
    public static class DescriptorImpl extends FlowDefinitionDescriptor {

        @Override
        public String getDisplayName() {
            return "Pipeline script from default " + PipelineBranchDefaultsProjectFactory.SCRIPT;
        }

    }

    /**
     * Want to display this in the r/o configuration for a branch project, but not offer it on standalone jobs or in any other context.
     */
    @Extension
    public static class HideMeElsewhere extends DescriptorVisibilityFilter {

        @Override
        public boolean filter(Object context, Descriptor descriptor) {
            if (descriptor instanceof DescriptorImpl) {
                return context instanceof WorkflowJob && ((WorkflowJob) context).getParent() instanceof PipelineMultiBranchDefaultsProject;
            }
            return true;
        }

    }
}
