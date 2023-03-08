//
//   Copyright 2018-2023  SenX S.A.S.
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

import io.warp10.WarpConfig;
import io.warp10.continuum.Configuration;
import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptStackFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;

public class IDENT extends NamedWarpScriptFunction implements WarpScriptStackFunction {
  
  public IDENT(String name) {
    super(name);
  }

  private static final String ident;

  static {
    ident = WarpConfig.getProperty(Configuration.WARP_IDENT);
  }

  @Override
  public Object apply(WarpScriptStack stack) throws WarpScriptException {
    stack.push(ident);
    return stack;
  }
}
