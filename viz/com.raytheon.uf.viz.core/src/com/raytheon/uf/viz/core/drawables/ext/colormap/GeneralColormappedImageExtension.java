/**
 * This software was developed and / or modified by Raytheon Company,
 * pursuant to Contract DG133W-05-CQ-1067 with the US Government.
 * 
 * U.S. EXPORT CONTROLLED TECHNICAL DATA
 * This software product contains export-restricted data whose
 * export/transfer/disclosure is restricted by U.S. law. Dissemination
 * to non-U.S. persons whether in the United States or abroad requires
 * an export license or other authorization.
 * 
 * Contractor Name:        Raytheon Company
 * Contractor Address:     6825 Pine Street, Suite 340
 *                         Mail Stop B8
 *                         Omaha, NE 68106
 *                         402.291.0100
 * 
 * See the AWIPS II Master Rights File ("Master Rights File.pdf") for
 * further licensing information.
 **/
package com.raytheon.uf.viz.core.drawables.ext.colormap;

import java.util.ArrayList;
import java.util.List;

import com.raytheon.uf.common.colormap.prefs.ColorMapParameters;
import com.raytheon.uf.viz.core.DrawableImage;
import com.raytheon.uf.viz.core.IGraphicsTarget;
import com.raytheon.uf.viz.core.data.IColorMapDataRetrievalCallback;
import com.raytheon.uf.viz.core.drawables.IColormappedImage;
import com.raytheon.uf.viz.core.drawables.PaintProperties;
import com.raytheon.uf.viz.core.drawables.ext.GraphicsExtension;
import com.raytheon.uf.viz.core.exception.VizException;

/**
 * General colormapped image extension. Uses
 * {@link IColorMapDataRetrievalCallback} to construct RenderedImages
 * 
 * <pre>
 * 
 * SOFTWARE HISTORY
 * 
 * Date         Ticket#    Engineer    Description
 * ------------ ---------- ----------- --------------------------
 * Nov 22, 2011            mschenke    Initial creation
 * Jul 27, 2016  5759      njensen     Cleanup
 * 
 * </pre>
 * 
 * @author mschenke
 */

public class GeneralColormappedImageExtension extends
        GraphicsExtension<IGraphicsTarget> implements
        IColormappedImageExtension {

    @Override
    public IColormappedImage initializeRaster(
            IColorMapDataRetrievalCallback dataCallback,
            ColorMapParameters colorMapParameters) {
        return new ColormappedImage(target, dataCallback, colorMapParameters);
    }

    @Override
    public int getCompatibilityValue(IGraphicsTarget target) {
        return Compatibilty.GENERIC;
    }

    @Override
    public boolean drawRasters(PaintProperties paintProps,
            DrawableImage... images) throws VizException {
        List<DrawableImage> renderables = new ArrayList<>();
        for (DrawableImage di : images) {
            if (di.getImage() instanceof ColormappedImage) {
                renderables.add(new DrawableImage(((ColormappedImage) di
                        .getImage()).getWrappedImage(), di.getCoverage()));
            } else {
                throw new IllegalArgumentException(this.getClass()
                        .getSimpleName()
                        + " cannot handle images of type: "
                        + di.getImage().getClass().getSimpleName());
            }
        }
        return target.drawRasters(paintProps,
                renderables.toArray(new DrawableImage[renderables.size()]));
    }

}
