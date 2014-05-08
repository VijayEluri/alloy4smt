package de.psi.alloy4smt.ast;

import edu.mit.csail.sdg.alloy4compiler.ast.Command;
import edu.mit.csail.sdg.alloy4compiler.ast.CommandScope;
import edu.mit.csail.sdg.alloy4compiler.ast.Sig;

public class Helpers {

	public static Sig getSigByName(Iterable<Sig> sigs, String name) {
	    Sig result = null;
	    for (Sig s: sigs) {
	        if (s.toString().equals(name)) {
	            result = s;
	            break;
	        }
	    }
	    return result;
	}

	public static Sig.Field getFieldByName(Iterable<Sig.Field> fields, String name) {
	    Sig.Field result = null;
	    for (Sig.Field field: fields) {
	        if (field.label.equals(name)) {
	            result = field;
	            break;
	        }
	    }
	    return result;
	}

    public static int getScope(Command command, Sig sig) {
		CommandScope scope = command.getScope(sig);
		int result;
		if (scope != null) {
			result = scope.endingScope;
		} else if (sig.isOne != null || sig.isLone != null) {
			result = 1;
		} else {
			result = command.overall < 0 ? 1 : command.overall;
		}
		return result;
    }
}
