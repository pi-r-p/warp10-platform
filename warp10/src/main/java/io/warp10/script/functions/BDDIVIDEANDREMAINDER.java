//
//   Copyright 2024  SenX S.A.S.
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

import java.math.BigDecimal;

import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStackFunction;

public class BDDIVIDEANDREMAINDER extends NamedWarpScriptFunction implements WarpScriptStackFunction {

  public BDDIVIDEANDREMAINDER(String name) {
    super(name);
  }

  @Override
  public Object apply(WarpScriptStack stack) throws WarpScriptException {
    Object o = stack.pop();

    BigDecimal bd2 = TOBD.toBigDecimal(getName(), o);

    o = stack.pop();

    BigDecimal bd1 = TOBD.toBigDecimal(getName(), o);

    BigDecimal[] quorem = bd1.divideAndRemainder(bd2);

    stack.push(quorem[0]);
    stack.push(quorem[1]);

    return stack;
  }
}
