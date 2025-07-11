package io.github.agentsoz.ees.shared;

/*-
 * #%L
 * Emergency Evacuation Simulator
 * %%
 * Copyright (C) 2014 - 2025 by its authors. See AUTHORS file.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class SharedPlans {
    public static void cleanupReceivedMessages(Map<UUID, Long> messages){
        try {
            Iterator<Map.Entry<UUID, Long>> iterator = messages.entrySet().iterator();

            long currentTimeStamp = SharedUtils.getSimTime();

            while (iterator.hasNext()) {
                long timeStamp = iterator.next().getValue();
                if (currentTimeStamp >= timeStamp + SharedConstants.CLEANUP_TIMER) {
                    iterator.remove();
                }
            }
        }catch (Exception e){
            System.err.println("cleanupReceivedMessages " + e.getMessage());
        }
    }
}
