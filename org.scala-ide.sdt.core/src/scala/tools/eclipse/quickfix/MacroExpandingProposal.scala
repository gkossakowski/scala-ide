package scala.tools.eclipse
package quickfix

import org.eclipse.jface.text.Position

class MacroExpandingProposal(s: String, pos: Position) 
  extends ExpandingProposalBase(s, "Explicitly expand the macro application: ", pos)