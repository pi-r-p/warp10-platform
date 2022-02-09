//
//   Copyright 2018  SenX S.A.S.
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.
//

package io.warp10.script.functions;

import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptStackFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;

/**
 * Configure the maximum number of pixels for images created on the stack
 */
public class MAXPIXELS extends NamedWarpScriptFunction implements WarpScriptStackFunction {
  
  public MAXPIXELS(String name) {
    super(name);
  }
  
  @Override
  public Object apply(WarpScriptStack stack) throws WarpScriptException {
    
    if (!stack.isAuthenticated()) {
      throw new WarpScriptException(getName() + " requires the stack to be authenticated.");
    }
    
    Object top = stack.pop();
    
    if (!(top instanceof Number)) {
      throw new WarpScriptException(getName() + " expects a numeric limit.");
    }
    
    long limit = ((Number) top).longValue();

    if (limit > (long) stack.getAttribute(WarpScriptStack.ATTRIBUTE_MAX_PIXELS_HARD)) {
      throw new WarpScriptException(getName() + " cannot extend limit past " + stack.getAttribute(WarpScriptStack.ATTRIBUTE_MAX_PIXELS_HARD));
    }

    stack.setAttribute(WarpScriptStack.ATTRIBUTE_MAX_PIXELS, limit);
    
    return stack;
  }
}
