//
//    Copyright 2020  SenX S.A.S.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//

package io.warp10.script.functions;

import com.geoxp.GeoXPLib;
import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStackFunction;

import java.util.ArrayList;

/**
 * Converts a GeoXPShape to a list of geocells (longs) or HHCode prefixes (strings).
 * It cannot convert to a bytes representation because they are limited to resolutions multiple of 4.
 */
public class GEOSHAPETO extends NamedWarpScriptFunction implements WarpScriptStackFunction {

  public GEOSHAPETO(String name) {
    super(name);
  }

  @Override
  public Object apply(WarpScriptStack stack) throws WarpScriptException {
    Object top = stack.pop();

    boolean toString = false;

    // Check for optional boolean for string conversion
    if (top instanceof Boolean) {
      toString = (Boolean) top;
      top = stack.pop();
    }

    if (!(top instanceof GeoXPLib.GeoXPShape)) {
      throw new WarpScriptException(getName() + " expects a " + TYPEOF.TYPE_GEOSHAPE + ".");
    }

    GeoXPLib.GeoXPShape shape = (GeoXPLib.GeoXPShape) top;
    long[] geocells = GeoXPLib.getCells(shape);
    ArrayList<Object> result = new ArrayList<Object>(geocells.length);

    if (toString) {
      for (long cell: geocells) {
        result.add(GEOCELLTO.geocellToHHCodePrefix(cell));
      }
    } else {
      for (long cell: geocells) {
        result.add(cell);
      }
    }

    stack.push(result);

    return stack;
  }
}
