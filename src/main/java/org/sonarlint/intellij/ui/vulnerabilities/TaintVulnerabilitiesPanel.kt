/*
 * SonarLint for IntelliJ IDEA
 * Copyright (C) 2015-2020 SonarSource
 * sonarlint@sonarsource.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonarlint.intellij.ui.vulnerabilities

import com.intellij.icons.AllIcons.General.Information
import com.intellij.ide.OccurenceNavigator
import com.intellij.ide.OccurenceNavigator.OccurenceInfo
import com.intellij.ide.plugins.newui.VerticalLayout
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.Splitter
import com.intellij.tools.SimpleActionGroup
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.tree.TreeUtil
import org.sonarlint.intellij.actions.OpenIssueInBrowserAction
import org.sonarlint.intellij.config.Settings.getGlobalSettings
import org.sonarlint.intellij.editor.SonarLintHighlighting
import org.sonarlint.intellij.issue.vulnerabilities.FoundTaintVulnerabilities
import org.sonarlint.intellij.issue.vulnerabilities.InvalidBinding
import org.sonarlint.intellij.issue.vulnerabilities.NoBinding
import org.sonarlint.intellij.issue.vulnerabilities.TaintVulnerabilitiesStatus
import org.sonarlint.intellij.ui.SonarLintConsole
import org.sonarlint.intellij.ui.SonarLintRulePanel
import org.sonarlint.intellij.ui.nodes.AbstractNode
import org.sonarlint.intellij.ui.nodes.IssueNode
import org.sonarlint.intellij.ui.nodes.LocalTaintVulnerabilityNode
import org.sonarlint.intellij.ui.tree.TaintVulnerabilityTree
import org.sonarlint.intellij.ui.tree.TaintVulnerabilityTreeModelBuilder
import org.sonarlint.intellij.util.SonarLintUtils.getService
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Desktop
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.net.URL
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeNode
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

private const val SPLIT_PROPORTION_PROPERTY = "SONARLINT_TAINT_VULNERABILITIES_SPLIT_PROPORTION"
private const val DEFAULT_SPLIT_PROPORTION = 0.65f
const val SONARQUBE_DOCUMENTATION_LINK = "https://docs.sonarqube.org/latest/user-guide/security-rules/"

private const val NO_BINDING_CARD_ID = "NO_BINDING_CARD"
private const val INVALID_BINDING_CARD_ID = "INVALID_BINDING_CARD"
private const val NO_ISSUES_CARD_ID = "NO_ISSUES_CARD"
private const val TREE_CARD_ID = "TREE_CARD"

private const val TOOLBAR_GROUP_ID = "TaintVulnerabilities"

class TaintVulnerabilitiesPanel(private val project: Project) : SimpleToolWindowPanel(false, true),
  OccurenceNavigator {

  private lateinit var rulePanel: SonarLintRulePanel
  private lateinit var tree: TaintVulnerabilityTree
  private lateinit var treeBuilder: TaintVulnerabilityTreeModelBuilder
  private val cards = JPanel(CardLayout())

  init {
    cards.add(centeredLabel("The project is not bound to SonarQube/SonarCloud"), NO_BINDING_CARD_ID)
    cards.add(centeredLabel("The project binding is invalid"), INVALID_BINDING_CARD_ID)
    cards.add(centeredLabel("No vulnerabilities found in currently opened files."), NO_ISSUES_CARD_ID)
    cards.add(createSplitter(
      ScrollPaneFactory.createScrollPane(createTree()),
      createRulePanel()),
      TREE_CARD_ID
    )

    val issuesPanel = JPanel(VerticalLayout(0))
    val globalSettings = getGlobalSettings()
    if (!globalSettings.isTaintVulnerabilitiesTabDisclaimerDismissed) {
      issuesPanel.add(createDisclaimer(), VerticalLayout.FILL_HORIZONTAL)
    }
    issuesPanel.add(cards, VerticalLayout.FILL_HORIZONTAL)
    setContent(issuesPanel)
    setupToolbar(OpenIssueInBrowserAction())
  }

  private fun centeredLabel(text: String): JPanel {
    val labelPanel = JPanel(BorderLayout())
    with(SimpleColoredComponent()) {
      setTextAlign(SwingConstants.CENTER)
      append(text)
      labelPanel.add(this, BorderLayout.CENTER)
    }
    return labelPanel
  }

  private fun expandTree() {
    if (treeBuilder.numberIssues() < 30) {
      TreeUtil.expandAll(tree)
    } else {
      tree.expandRow(0)
    }
  }

  private fun createDisclaimer(): StripePanel {
    val stripePanel = StripePanel("This tab displays taint vulnerabilities coming from the connected server. There is no local analysis", Information)
    stripePanel.addAction("Learn more", object : AnAction() {
      override fun actionPerformed(e: AnActionEvent) {
        val desktop = Desktop.getDesktop()
        try {
          desktop.browse(URL(SONARQUBE_DOCUMENTATION_LINK).toURI())
        } catch (ex: Exception) {
          SonarLintConsole.get(project).error("Error opening link: $SONARQUBE_DOCUMENTATION_LINK", ex)
        }
      }
    })
    stripePanel.addAction("Dismiss", object : AnAction() {
      override fun actionPerformed(e: AnActionEvent) {
        getGlobalSettings().dismissTaintVulnerabilitiesTabDisclaimer()
        stripePanel.parent.remove(stripePanel)
      }
    })
    return stripePanel
  }

  private fun setupToolbar(action: AnAction) {
    val group = SimpleActionGroup()
    group.add(action)
    val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_GROUP_ID, group, false)
    toolbar.setTargetComponent(this)
    val toolBarBox = Box.createHorizontalBox()
    toolBarBox.add(toolbar.component)
    setToolbar(toolBarBox)
    toolbar.component.isVisible = true
  }

  private fun showCard(id: String) {
    val cl = cards.layout as CardLayout
    cl.show(cards, id)
  }

  fun populate(status: TaintVulnerabilitiesStatus) {
    when (status) {
      is NoBinding -> showCard(NO_BINDING_CARD_ID)
      is InvalidBinding -> showCard(INVALID_BINDING_CARD_ID)
      is FoundTaintVulnerabilities -> {
        if (status.isEmpty()) {
          showCard(NO_ISSUES_CARD_ID)
        } else {
          showCard(TREE_CARD_ID)
          treeBuilder.updateModel(status.byFile)
          expandTree()
        }
      }
    }
  }

  private fun createRulePanel(): JBTabbedPane {
    // Rule panel
    rulePanel = SonarLintRulePanel(project)
    val scrollableRulePanel = ScrollPaneFactory.createScrollPane(
      rulePanel.panel,
      ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
      ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    )
    scrollableRulePanel.verticalScrollBar.unitIncrement = 10
    val detailsTab = JBTabbedPane()
    detailsTab.addTab("Rule", null, scrollableRulePanel, "Details about the rule")
    return detailsTab
  }

  private fun createSplitter(c1: JComponent, c2: JComponent): JComponent {
    val savedProportion = PropertiesComponent.getInstance(project).getFloat(SPLIT_PROPORTION_PROPERTY, DEFAULT_SPLIT_PROPORTION)
    val splitter = Splitter(false)
    splitter.firstComponent = c1
    splitter.secondComponent = c2
    splitter.proportion = savedProportion
    splitter.setHonorComponentsMinimumSize(true)
    splitter.addPropertyChangeListener(Splitter.PROP_PROPORTION) {
      PropertiesComponent.getInstance(project).setValue(
        SPLIT_PROPORTION_PROPERTY, splitter.proportion.toString()
      )
    }
    return splitter
  }

  private fun issueTreeSelectionChanged() {
    val selectedNodes = tree.getSelectedNodes(LocalTaintVulnerabilityNode::class.java, null)
    val highlighting = getService(project, SonarLintHighlighting::class.java)
    if (selectedNodes.isNotEmpty()) {
      val issue = selectedNodes[0].issue()
      rulePanel.setRuleKey(issue.ruleKey())
      highlighting.highlight(issue)
    } else {
      rulePanel.setRuleKey(null)
      highlighting.removeHighlights()
    }
  }

  private fun createTree(): TaintVulnerabilityTree {
    treeBuilder = TaintVulnerabilityTreeModelBuilder()
    tree = TaintVulnerabilityTree(project, treeBuilder.model)
    tree.addTreeSelectionListener { issueTreeSelectionChanged() }
    tree.addKeyListener(object : KeyAdapter() {
      override fun keyPressed(e: KeyEvent) {
        if (KeyEvent.VK_ESCAPE == e.keyCode) {
          val highlighting = getService(
            project,
            SonarLintHighlighting::class.java
          )
          highlighting.removeHighlights()
        }
      }
    })
    tree.addFocusListener(object : FocusAdapter() {
      override fun focusGained(e: FocusEvent) {
        if (!e.isTemporary) {
          issueTreeSelectionChanged()
        }
      }
    })
    tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    return tree
  }

  private fun occurrence(node: IssueNode?): OccurenceInfo? {
    if (node == null) {
      return null
    }
    val path = TreePath(node.path)
    tree.selectionModel.selectionPath = path
    tree.scrollPathToVisible(path)
    val range = node.issue().getRange()
    val startOffset = range?.startOffset ?: 0
    return OccurenceInfo(
      OpenFileDescriptor(project, node.issue().psiFile().virtualFile, startOffset),
      -1,
      -1
    )
  }

  override fun hasNextOccurence(): Boolean {
    // relies on the assumption that a TreeNodes will always be the last row in the table view of the tree
    val path = tree.selectionPath ?: return false
    val node = path.lastPathComponent as DefaultMutableTreeNode
    return if (node is IssueNode) {
      tree.rowCount != tree.getRowForPath(path) + 1
    } else {
      node.childCount > 0
    }
  }

  override fun hasPreviousOccurence(): Boolean {
    val path = tree.selectionPath ?: return false
    val node = path.lastPathComponent as DefaultMutableTreeNode
    return node is IssueNode && !isFirst(node)
  }

  private fun isFirst(node: TreeNode): Boolean {
    val parent = node.parent
    return parent == null || parent.getIndex(node) == 0 && isFirst(parent)
  }

  override fun goNextOccurence(): OccurenceInfo? {
    val path = tree.selectionPath ?: return null
    return occurrence(treeBuilder.getNextIssue(path.lastPathComponent as AbstractNode))
  }

  override fun goPreviousOccurence(): OccurenceInfo? {
    val path = tree.selectionPath ?: return null
    return occurrence(treeBuilder.getPreviousIssue(path.lastPathComponent as AbstractNode))
  }

  override fun getNextOccurenceActionName(): String {
    return "Next Issue"
  }

  override fun getPreviousOccurenceActionName(): String {
    return "Previous Issue"
  }

}