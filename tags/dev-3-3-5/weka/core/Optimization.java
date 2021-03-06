/*
 *    This program is free software; you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation; either version 2 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program; if not, write to the Free Software
 *    Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */

/*
 *    Optimization.java
 *    Copyright (C) 2003 Xin Xu
 *
 */

package weka.core;

import java.util.*;
import java.io.*;

/**
 * Implementation of Active-sets method with BFGS update
 * to solve optimization problem with only bounds constraints in 
 * multi-dimensions.  In this implementation we consider both the lower and higher 
 * bound constraints.  <p>
 *
 * Here is the sketch of our searching strategy, and the detailed description
 * of the algorithm can be found in the Appendix of Xin Xu's MSc thesis:<p>
 * Initialize everything, incl. initial value, direction, etc.<p>
 * LOOP (main algorithm):<br>
 * 
 * 1. Perform the line search using the directions for free variables<br>
 * 1.1  Check all the bounds that are not "active" (i.e. binding variables)
 *      and compute the feasible step length to the bound for each of them<br>
 * 1.2  Pick up the least feasible step length, say \alpha, and set it as 
 *      the upper bound of the current step length, i.e. 0<\lambda<=\alpha<br>
 * 1.3  Search for any possible step length<=\alpha that can result the 
 *      "sufficient function decrease" (\alpha condition) AND "positive definite 
 *      inverse Hessian" (\beta condition), if possible, using SAFEGUARDED polynomial 
 *      interpolation.  This step length is "safe" and thus
 *      is used to compute the next value of the free variables .<br>
 * 1.4  Fix the variable(s) that are newly bound to its constraint(s).<p>     
 *
 * 2. Check whether there is convergence of all variables or their gradients.
 *    If there is, check the possibilities to release any current bindings of
 *    the fixed variables to their bounds based on the "reliable" second-order 
 *    Lagarange multipliers if available.  If it's available and negative for one
 *    variable, then release it.  If not available, use first-order Lagarange 
 *    multiplier to test release.  If there is any released variables, STOP the loop.
 *    Otherwise update the inverse of Hessian matrix and gradient for the newly 
 *    released variables and CONTINUE LOOP.<p>
 *
 * 3. Use BFGS formula to update the inverse of Hessian matrix.  Note the 
 *    already-fixed variables must have zeros in the corresponding entries
 *    in the inverse Hessian.<p>  
 *
 * 4. Compute the new (newton) search direction d=H^{-1}*g, where H^{-1} is the 
 *    inverse Hessian and g is the Jacobian.  Note that again, the already-
 *    fixed variables will have zero direction.<p>
 *
 * ENDLOOP<p>
 *
 * A typical usage of this class is to create your own subclass of this class
 * and provide the objective function and gradients as follows:
 * <code>
 * class MyOpt extends Optimization{
 *   // Provide the objective function
 *   protected double objectiveFunction(double[] x){
 *       // How to calculate your objective function...
 *       // ...
 *   }
 *
 *   // Provide the first derivatives
 *   protected double[] evaluateGradient(double[] x){
 *       // How to calculate the gradient of the objective function...
 *       // ...
 *   } 
 *
 *   // If possible, provide the index^{th} row of the Hessian matrix
 *   protected double[] evaluateHessian(double[] x, int index){
 *      // How to calculate the index^th variable's second derivative
 *      // ...
 *   }
 * } 
 *
 * // When it's the time to use it, in some routine(s) of other class...
 * MyOpt opt = new MyOpt();
 * 
 * // Set up initial variable values and bound constraints
 * double[] x = new double[numVariables];
 * // Lower and upper bounds: 1st row is lower bounds, 2nd is upper
 * double[] constraints = new double[2][numVariables];
 * ...
 *
 * // Find the minimum, 200 iterations as default
 * x = opt.findArgmin(x, constraints); 
 * while(x == null){  // 200 iterations are not enough
 *    x = opt.getVarbValues();  // Try another 200 iterations
 *    x = opt.findArgmin(x, constraints);
 * }
 *
 * // The minimal function value
 * double minFunction = opt.getMinFunction();
 * ...
 * </code>  
 * It is recommended that Hessian values be provided so that the second-order 
 * Lagrangian multiplier estimate can be calcluated.  However, if it is not provided, 
 * there is no need to override the <code>evaluateHessian()</code> function.<p>
 *
 * REFERENCES:<br>
 * The whole model algorithm is adapted from Chapter 5 and other related chapters in 
 * Gill, Murray and Wright(1981) "Practical Optimization", Academic Press.
 * and Gill and Murray(1976) "Minimization Subject to Bounds on the Variables", NPL 
 * Report NAC72, while Chong and Zak(1996) "An Introduction to Optimization", 
 * John Wiley & Sons, Inc. provides us a brief but helpful introduction to the method. <p>
 *
 * Dennis and Schnabel(1983) "Numerical Methods for Unconstrained Optimization and 
 * Nonlinear Equations", Prentice-Hall Inc. and Press et al.(1992) "Numeric Recipe in C",
 * Second Edition, Cambridge University Press. are consulted for the polynomial
 * interpolation used in the line search implementation.  <p>
 *
 * The Hessian modification in BFGS update uses Cholesky factorization and two rank-one 
 * modifications:<br>
 * Bk+1 = Bk + (Gk*Gk')/(Gk'Dk) + (dGk*(dGk)'))/[alpha*(dGk)'*Dk]. <br>
 * where Gk is the gradient vector, Dk is the direction vector and alpha is the
 * step rate.  <br>
 * This method is due to Gill, Golub, Murray and Saunders(1974) ``Methods for Modifying 
 * Matrix Factorizations'', Mathematics of Computation, Vol.28, No.126, pp 505-535.
 * <p>
 *
 * @author Xin Xu (xx5@cs.waikato.ac.nz)
 * @version $Revision: 1.1 $ 
 */
public abstract class Optimization{
    
    protected double m_ALF = 1.0e-4;

    protected double m_BETA = 0.9;    

    protected double m_TOLX = 1.0e-6;
   
    protected double m_STPMX = 100.0;
    
    protected int m_MAXITS = 200;
    
    protected static boolean m_Debug = false;
    
    // function value
    protected double m_f;    
 
    // G'*p
    private double m_Slope;
    
    // Test if zero step in lnsrch
    private boolean m_IsZeroStep = false;
    
    // Used when iteration overflow occurs
    private double[] m_X;
    
    // Compute machine precision
    protected static double m_Epsilon, m_Zero; 
    static {
	m_Epsilon=1.0;
	while(1.0+m_Epsilon > 1.0){
	    m_Epsilon /= 2.0;	    
	}
	m_Epsilon *= 2.0;
	m_Zero = Math.sqrt(m_Epsilon);
	if (m_Debug)
	    System.err.print("Machine precision is "+m_Epsilon+
			     " and zero set to "+m_Zero);
    }
    
    /* 
     * Subclass should implement this procedure to evaluate objective
     * function to be minimized
     * 
     * @param x the variable values
     * @return the objective function value
     */
    protected abstract double objectiveFunction(double[] x);

    /* 
     * Subclass should implement this procedure to evaluate gradient
     * of the objective function
     * 
     * @param x the variable values
     * @return the gradient vector
     */
    protected abstract double[] evaluateGradient(double[] x);

    /* 
     * Subclass is recommended to override this procedure to evaluate second-order
     * gradient of the objective function.  If it's not provided, it returns
     * null.
     *
     * @param x the variables
     * @param index the row index in the Hessian matrix
     * @return one row (the row #index) of the Hessian matrix, null as default
     */
    protected double[] evaluateHessian(double[] x, int index){
	return null;
    }

    /**
     * Get the minimal function value
     *
     * @return minimal function value found
     */
    public double getMinFunction(){ return m_f;}

    /**
     * Set the maximal number of iterations in searching (Default 200)
     *
     * @param it the maximal number of iterations
     */
    public void setMaxIteration(int it){ m_MAXITS=it; }
      
    /**
     * Set whether in debug mode
     *
     * @param db use debug or not
     */
    public void setDebug(boolean db){ m_Debug = db; }
    
    /**
     * Get the variable values.  Only needed when iterations exceeds 
     * the max threshold.
     *
     * @return the current variable values
     */
    public double[] getVarbValues(){ return m_X; }
    
    /**
     * Find a new point x in the direction p from a point xold at which the
     * value of the function has decreased sufficiently, the positive 
     * definiteness of B matrix (approximation of the inverse of the Hessian)
     * is preserved and no bound constraints are violated.  Details see "Numerical 
     * Methods for Unconstrained Optimization and Nonlinear Equations".
     * "Numeric Recipes in C" was also consulted.
     *
     * @param xold old x value 
     * @param gradient gradient at that point
     * @param direct direction vector
     * @param stpmax maximum step length
     * @param isFixed indicating whether a variable has been fixed
     * @param nwsBounds non-working set bounds.  Means these variables are free and
     *                  subject to the bound constraints in this step
     * @param wsBdsIndx index of variables that has working-set bounds.  Means
     *                  these variables are already fixed and no longer subject to
     *                  the constraints
     * @return new value along direction p from xold, null if no step was taken
     * @exception Exception if an error occurs
     */
    public double[] lnsrch(double[] xold, double[] gradient, 
			   double[] direct, double stpmax,
			   boolean[] isFixed, double[][] nwsBounds,
			   FastVector wsBdsIndx)
	throws Exception {
	
	int i, j, k,len=xold.length, 
	    fixedOne=-1; // idx of variable to be fixed
	double alam, alamin; // lambda to be found, and its lower bound
	
	// For convergence and bound test
	double temp,test,alpha=Double.POSITIVE_INFINITY,fold=m_f,sum; 
	
	// For cubic interpolation
	double a,alam2=0,b,disc=0,maxalam=1.0,rhs1,rhs2,tmplam;
	
	double[] x = new double[len]; // New variable values
	
	// Scale the step 
	for (sum=0.0,i=0;i<len;i++){
	    if(!isFixed[i]) // For fixed variables, direction = 0
		sum += direct[i]*direct[i];
	}	
	sum = Math.sqrt(sum);
	
	if (m_Debug)
	    System.err.println("fold:  "+Utils.doubleToString(fold,10,7)+"\n"+
			       "sum:  "+Utils.doubleToString(sum,10,7)+"\n"+
			       "stpmax:  "+Utils.doubleToString(stpmax,10,7));
	if (sum > stpmax){
	    for (i=0;i<len;i++)
		if(!isFixed[i])
		    direct[i] *= stpmax/sum;		
	}
	else
	    maxalam = stpmax/sum;
	
	// Compute initial rate of decrease, g'*d 
	m_Slope=0.0;
	for (i=0;i<len;i++){
	    x[i] = xold[i];
	    if(!isFixed[i])
		m_Slope += gradient[i]*direct[i];
	}
	
	if (m_Debug)
	    System.err.print("slope:  " + Utils.doubleToString(m_Slope,10,7)+ "\n");
	
	// Slope too small
	if(Math.abs(m_Slope)<=m_Zero){
	    if (m_Debug)
		System.err.println("Gradient and direction orthogonal -- "+
				   "Min. found with current fixed variables"+
				   " (or all variables fixed). Try to release"+
				   " some variables now.");
	    return x;
	}
	
	// Err: slope > 0
	if(m_Slope > m_Zero){
	    if(m_Debug)
		for(int h=0; h<x.length; h++)
		    System.err.println(h+": isFixed="+isFixed[h]+", x="+
				       x[h]+", grad="+gradient[h]+", direct="+
				       direct[h]);
	    throw new Exception("g'*p positive! -- Try to debug from here: line 263.");
	}
	
	// Compute LAMBDAmin and upper bound of lambda--alpha
	test=0.0;
	for(i=0;i<len;i++){	    
	    if(!isFixed[i]){// No need for fixed variables
		temp=Math.abs(direct[i])/Math.max(Math.abs(x[i]),1.0);
		if (temp > test) test=temp;
	    }
	}
	
	if(test>m_Zero) // Not converge
	    alamin = m_TOLX/test;
	else{
	    if (m_Debug)
		System.err.println("Zero directions for all free variables -- "+
				   "Min. found with current fixed variables"+
				   " (or all variables fixed). Try to release"+
				   " some variables now.");
	    return x;
	}
		
	// Check whether any non-working-set bounds are "binding"
	for(i=0;i<len;i++){
	    if(!isFixed[i]){// No need for fixed variables
		double alpi;
		if((direct[i]<-m_Epsilon) && !Double.isNaN(nwsBounds[0][i])){//Not feasible
		    alpi = (nwsBounds[0][i]-xold[i])/direct[i];
		    if(alpi <= m_Zero){ // Zero
			if (m_Debug)
			    System.err.println("Fix variable "+i+
					       " to lower bound "+ nwsBounds[0][i]+
					       " from value "+ xold[i]);
			x[i] = nwsBounds[0][i];
			isFixed[i]=true; // Fix this variable
			alpha = 0.0;
			nwsBounds[0][i]=Double.NaN; //Add cons. to working set
			wsBdsIndx.addElement(new Integer(i));
		    }
		    else if(alpha > alpi){ // Fix one variable in one iteration
			alpha = alpi;
			fixedOne = i;
		    }			
		}
		else if((direct[i]>m_Epsilon) && !Double.isNaN(nwsBounds[1][i])){//Not feasible
		    alpi = (nwsBounds[1][i]-xold[i])/direct[i];
		    if(alpi <= m_Zero){ // Zero
			if (m_Debug)
			    System.err.println("Fix variable "+i+
					       " to upper bound "+ nwsBounds[1][i]+
					       " from value "+ xold[i]);
			x[i] = nwsBounds[1][i];
			isFixed[i]=true; // Fix this variable
			alpha = 0.0;
			nwsBounds[1][i]=Double.NaN; //Add cons. to working set
			wsBdsIndx.addElement(new Integer(i));
		    }
		    else if(alpha > alpi){
			alpha = alpi;
			fixedOne = i;
		    }			
		}				
	    }
	}	
	
	if (m_Debug){
	    System.err.println("alamin: " + Utils.doubleToString(alamin,10,7));
	    System.err.println("alpha: " + Utils.doubleToString(alpha,10,7));
	}
	
	if(alpha <= m_Zero){ // Zero	   
	    m_IsZeroStep = true;
	    if (m_Debug)
		System.err.println("Alpha too small, try again");
	    return x;
	}
	
	alam = alpha; // Always try full feasible newton step 
	if(alam > 1.0)
	    alam = 1.0;
	
	// Iteration of one newton step, if necessary, backtracking is done
	double initF=fold, // Initial function value
	    hi=alam, lo=alam, newSlope=0, fhi=m_f, flo=m_f;// Variables used for beta condition
	double[] newGrad;  // Gradient on the new variable values
	
	kloop:
	for (k=0;;k++) {
	    if(m_Debug)
		System.err.println("\nIteration: " + k);
	    
	    for (i=0;i<len;i++){
		if(!isFixed[i]){
		    x[i] = xold[i]+alam*direct[i];  // Compute xnew
		    if(!Double.isNaN(nwsBounds[0][i]) && (x[i]<nwsBounds[0][i])){    
			x[i] = nwsBounds[0][i]; //Rounding error	
		    }
		    else if(!Double.isNaN(nwsBounds[1][i]) && (x[i]>nwsBounds[1][i])){		
			x[i] = nwsBounds[1][i]; //Rounding error	
		    }
		}
	    }
	    
	    m_f = objectiveFunction(x);    // Compute fnew
	    
	    while(Double.isInfinite(m_f)){ // Avoid infinity
		if(m_Debug)
		    System.err.println("Too large m_f.  Shrink step by half.");
		alam *= 0.5; // Shrink by half
		if(alam <= m_Epsilon){
		    if(m_Debug)
			System.err.println("Wrong starting points, change them!");
		    return x;
		}
		
		for (i=0;i<len;i++)
		    if(!isFixed[i])
			x[i] = xold[i]+alam*direct[i]; 
		
		m_f = objectiveFunction(x); 
		initF = Double.POSITIVE_INFINITY;
	    }
	    
	    if(m_Debug) {
		System.err.println("obj. function: " + 
				 Utils.doubleToString(m_f, 10, 7));
		System.err.println("threshold: " + 
				 Utils.doubleToString(fold+m_ALF*alam*m_Slope,10,7));
	    }
	    
	    if(m_f<=fold+m_ALF*alam*m_Slope){// Alpha condition: sufficient function decrease
		if(m_Debug)		
		    System.err.println("Sufficient function decrease (alpha condition): "); 
		newGrad = evaluateGradient(x);
		for(newSlope=0.0,i=0; i<len; i++)
		    if(!isFixed[i])
			newSlope += newGrad[i]*direct[i];

		if(newSlope >= m_BETA*m_Slope){ // Beta condition: ensure pos. defnty.	
		    if(m_Debug)		
			System.err.println("Increasing derivatives (beta condition): "); 	

		    if((fixedOne!=-1) && (alam>=alpha)){ // Has bounds and over
			if(direct[fixedOne] > 0){
			    x[fixedOne] = nwsBounds[1][fixedOne]; // Avoid rounding error
			    nwsBounds[1][fixedOne]=Double.NaN; //Add cons. to working set
			}
			else{
			    x[fixedOne] = nwsBounds[0][fixedOne]; // Avoid rounding error
			    nwsBounds[0][fixedOne]=Double.NaN; //Add cons. to working set
			}
			
			if(m_Debug)
			    System.err.println("Fix variable "
					       +fixedOne+" to bound "+ x[fixedOne]+
					       " from value "+ xold[fixedOne]);
			isFixed[fixedOne]=true; // Fix the variable
			wsBdsIndx.addElement(new Integer(fixedOne));
		    }		
		    return x;
		}
		else if(k==0){ // First time: increase alam 
		    // Search for the smallest value not complying with alpha condition
		    double upper = Math.min(alpha,maxalam); 
		    if(m_Debug)
			System.err.println("Alpha condition holds, increase alpha... ");
		    while((alam>=upper) || (m_f>fold+m_ALF*alam*m_Slope)){
			lo = alam;
			flo = m_f;
			alam *= 2.0;
			if(alam>=upper)
			    alam=upper;

			for (i=0;i<len;i++)
			    if(!isFixed[i])
				x[i] = xold[i]+alam*direct[i];
			m_f = objectiveFunction(x);
			newGrad = evaluateGradient(x);
			for(newSlope=0.0,i=0; i<len; i++)
			    if(!isFixed[i])
				newSlope += newGrad[i]*direct[i];

			if(newSlope >= m_BETA*m_Slope){
			    if (m_Debug)		
				System.err.println("Increasing derivatives (beta condition): ");

			    if((fixedOne!=-1) && (alam>=alpha)){ // Has bounds and over
				if(direct[fixedOne] > 0){
				    x[fixedOne] = nwsBounds[1][fixedOne]; // Avoid rounding error
				    nwsBounds[1][fixedOne]=Double.NaN; //Add cons. to working set
				}
				else{
				    x[fixedOne] = nwsBounds[0][fixedOne]; // Avoid rounding error
				    nwsBounds[0][fixedOne]=Double.NaN; //Add cons. to working set
				}
				
				if(m_Debug)
				    System.err.println("Fix variable "
						       +fixedOne+" to bound "+ x[fixedOne]+
						       " from value "+ xold[fixedOne]);
				isFixed[fixedOne]=true; // Fix the variable
				wsBdsIndx.addElement(new Integer(fixedOne));
			    }		 				    
			    return x;
			}
		    }
		    hi = alam;
		    fhi = m_f;			
		    break kloop;
		}
		else{
		    if(m_Debug)
			System.err.println("Alpha condition holds.");
		    hi = alam2; lo = alam; flo = m_f;
		    break kloop;
		}		    
	    }        
	    else if (alam < alamin) { // No feasible lambda found       
		if(initF<fold){ 
		    alam = Math.min(1.0,alpha);
		    for (i=0;i<len;i++)
			if(!isFixed[i])
			    x[i] = xold[i]+alam*direct[i]; //Still take Alpha
		    
		    if (m_Debug)
			System.err.println("No feasible lambda: still take"+
					   " alpha="+alam);
		    
		    if((fixedOne!=-1) && (alam>=alpha)){ // Has bounds and over
			if(direct[fixedOne] > 0){
			    x[fixedOne] = nwsBounds[1][fixedOne]; // Avoid rounding error
			    nwsBounds[1][fixedOne]=Double.NaN; //Add cons. to working set
			}
			else{
			    x[fixedOne] = nwsBounds[0][fixedOne]; // Avoid rounding error
			    nwsBounds[0][fixedOne]=Double.NaN; //Add cons. to working set
			}
			
			if(m_Debug)
			    System.err.println("Fix variable "
					       +fixedOne+" to bound "+ x[fixedOne]+
					       " from value "+ xold[fixedOne]);
			isFixed[fixedOne]=true; // Fix the variable
			wsBdsIndx.addElement(new Integer(fixedOne));
		    }		 		    
		}
		else{   // Convergence on delta(x)
		    for(i=0;i<len;i++) 
			x[i]=xold[i];
		    m_f=fold;
		    if (m_Debug)
			System.err.println("Cannot find feasible lambda"); 
		}
		
		return x; 
	    }
	    else { // Backtracking by polynomial interpolation
		if(k==0){ // First time backtrack: quadratic interpolation
		    if(!Double.isInfinite(initF))
			initF = m_f;		    
		    // lambda = -g'(0)/(2*g''(0))
		    tmplam = -0.5*alam*m_Slope/((m_f-fold)/alam-m_Slope);
		}
		else {    // Subsequent backtrack: cubic interpolation 
		    rhs1 = m_f-fold-alam*m_Slope;
		    rhs2 = fhi-fold-alam2*m_Slope;
		    a=(rhs1/(alam*alam)-rhs2/(alam2*alam2))/(alam-alam2);
		    b=(-alam2*rhs1/(alam*alam)+alam*rhs2/(alam2*alam2))/(alam-alam2);
		    if (a == 0.0) tmplam = -m_Slope/(2.0*b);
		    else {
			disc=b*b-3.0*a*m_Slope;
			if (disc < 0.0) disc = 0.0;
			tmplam=(-b+Math.sqrt(disc))/(3.0*a);
		    }
		    if (m_Debug)
			System.err.print("newstuff: \n" + 
					 "a:   " + Utils.doubleToString(a,10,7)+ "\n" +
					 "b:   " + Utils.doubleToString(b,10,7)+ "\n" +
					 "disc:   " + Utils.doubleToString(disc,10,7)+ "\n" +
					 "tmplam:   " + tmplam + "\n" +
					 "alam:   " + Utils.doubleToString(alam,10,7)+ "\n");	
		    if (tmplam>0.5*alam)
			tmplam=0.5*alam;             // lambda <= 0.5*lambda_old
		}
	    }
	    alam2=alam;
	    fhi=m_f;
	    alam=Math.max(tmplam,0.1*alam);          // lambda >= 0.1*lambda_old
	    
	    if(alam>alpha){
		throw new Exception("Sth. wrong in lnsrch:"+
				    "Lambda infeasible!(lambda="+alam+
				    ", alpha="+alpha+", upper="+tmplam+
				    "|"+(-alpha*m_Slope/(2.0*((m_f-fold)/alpha-m_Slope)))+
				    ", m_f="+m_f+", fold="+fold+
				    ", slope="+m_Slope);
	    }	    
	} // Endfor(k=0;;k++)
	
	// Quadratic interpolation between lamda values between lo and hi.
	// If cannot find a value satisfying beta condition, use lo.
	double ldiff = hi-lo, lincr;
	if(m_Debug)
	    System.err.println("Last stage of searching for beta condition (alam between "
			       +Utils.doubleToString(lo,10,7)+" and "
			       +Utils.doubleToString(hi,10,7)+")...");
	while((newSlope<m_BETA*m_Slope) && (ldiff>=alamin)){
	    lincr = -0.5*newSlope*ldiff*ldiff/(fhi-flo-newSlope*ldiff);
	    if(lincr<0.2*ldiff) lincr=0.2*ldiff;
	    alam = lo+lincr;

	    for (i=0;i<len;i++)
		if(!isFixed[i])
		    x[i] = xold[i]+alam*direct[i];
	    m_f = objectiveFunction(x);

	    if(m_f>fold+m_ALF*alam*m_Slope){ 
		// Alpha condition fails, shrink lambda_upper
		ldiff = lincr;
		fhi = m_f;
	    }	    
	    else{ // Alpha condition holds	    
		newGrad = evaluateGradient(x);
		for(newSlope=0.0,i=0; i<len; i++)
		    if(!isFixed[i])
			newSlope += newGrad[i]*direct[i];
		
		if(newSlope < m_BETA*m_Slope){
		    // Beta condition fails, shrink lambda_lower
		    lo = alam;
		    ldiff -= lincr;
		    flo = m_f;
		}
	    }
	} 
	
	if(newSlope < m_BETA*m_Slope){ // Cannot satisfy beta condition, take lo
	    if(m_Debug)
		System.err.println("Beta condition cannot be satisfied, take alpha condition");
	    alam=lo;
	    for (i=0;i<len;i++)
		if(!isFixed[i])
		    x[i] = xold[i]+alam*direct[i];
	    m_f = flo;
	}
	else if(m_Debug)
	    System.err.println("Both alpha and beta conditions are satisfied. alam="
			       +Utils.doubleToString(alam,10,7));
	
	if((fixedOne!=-1) && (alam>=alpha)){ // Has bounds and over
	    if(direct[fixedOne] > 0){
		x[fixedOne] = nwsBounds[1][fixedOne]; // Avoid rounding error
		nwsBounds[1][fixedOne]=Double.NaN; //Add cons. to working set
	    }
	    else{
		x[fixedOne] = nwsBounds[0][fixedOne]; // Avoid rounding error
		nwsBounds[0][fixedOne]=Double.NaN; //Add cons. to working set
	    }
	    
	    if(m_Debug)
		System.err.println("Fix variable "
				   +fixedOne+" to bound "+ x[fixedOne]+
				   " from value "+ xold[fixedOne]);
	    isFixed[fixedOne]=true; // Fix the variable
	    wsBdsIndx.addElement(new Integer(fixedOne));
	}
	
	return x;
    }
    
    /**
     * Main algorithm.  Descriptions see "Practical Optimization"
     *
     * @param initX initial point of x, assuming no value's on the bound!
     * @param constraints the bound constraints of each variable
     *                    constraints[0] is the lower bounds and 
     *                    constraints[1] is the upper bounds
     * @return the solution of x, null if number of iterations not enough
     * @exception Exception if an error occurs
     */
    public double[] findArgmin(double[] initX, double[][] constraints) 
	throws Exception{
	int l = initX.length;
	
	// Initially all variables are free, all bounds are constraints of
	// non-working-set constraints
	boolean[] isFixed = new boolean[l];
	double[][] nwsBounds = new double[2][l];
	// Record indice of fixed variables, simply for efficiency
	FastVector wsBdsIndx = new FastVector(); 
	// Vectors used to record the variable indices to be freed 	
	FastVector toFree=null, oldToFree=null;	

	// Initial value of obj. function, gradient and inverse of the Hessian
	m_f = objectiveFunction(initX);
	double sum=0;
	double[] grad=evaluateGradient(initX), oldGrad, oldX,
	    deltaGrad=new double[l], deltaX=new double[l],
	    direct = new double[l], x = new double[l];
	Matrix L = new Matrix(l, l),// Lower triangle of Cholesky factor 
	    D = new Matrix(l, l);   // Diagonal of Cholesky factor
	for(int i=0; i<l; i++){
	    L.setRow(i, new double[l]);
	    L.setElement(i,i,1.0);
	    D.setRow(i, new double[l]);
	    D.setElement(i,i,1.0);
	    direct[i] = -grad[i];
	    sum += grad[i]*grad[i];
	    x[i] = initX[i];
	    nwsBounds[0][i] = constraints[0][i];
	    nwsBounds[1][i] = constraints[1][i];
	    isFixed[i] = false;
	}	
	double stpmax = m_STPMX*Math.max(Math.sqrt(sum), l);
	
	iterates:
	for(int step=0; step < m_MAXITS; step++){
	    if (m_Debug)
		System.err.println("\nIteration # " + step + ":");	    
	    
	    // Try at most one feasible newton step, i.e. 0<lamda<=alpha
	    oldX = x;
	    oldGrad = grad;
	    
	    // Also update grad
	    if (m_Debug)
		System.err.println("Line search ... ");
	    m_IsZeroStep = false;
	    x=lnsrch(x, grad, direct, stpmax, 
		     isFixed, nwsBounds, wsBdsIndx);
	    if (m_Debug)
		System.err.println("Line search finished.");
	    
	    if(m_IsZeroStep){ // Zero step, simply delete rows/cols of D and L
		for(int f=0; f<wsBdsIndx.size(); f++){
		    int idx=((Integer)wsBdsIndx.elementAt(f)).intValue();
		    L.setRow(idx, new double[l]);
		    L.setColumn(idx, new double[l]);
		    D.setElement(idx, idx, 0.0);
		}		
		grad = evaluateGradient(x);
		step--;
	    }
	    else{
		// Check converge on x
		boolean finish = false;
		double test=0.0;
		for(int h=0; h<l; h++){
		    deltaX[h] = x[h]-oldX[h];
		    double tmp=Math.abs(deltaX[h])/
			Math.max(Math.abs(x[h]), 1.0);
		    if(tmp > test) test = tmp;				    
		}
		if(test < m_Zero){
		    if (m_Debug)
			System.err.println("\nDeltaX converge: "+test);
		    finish = true;
		}
		
		// Check zero gradient	    
		grad = evaluateGradient(x);
		test=0.0;
		double denom=0.0, dxSq=0.0, dgSq=0.0, newlyBounded=0.0; 
		for(int g=0; g<l; g++){
		    if(!isFixed[g]){ 		   
			deltaGrad[g] = grad[g] - oldGrad[g];		  
			// Calculate the denominators			    
			denom += deltaX[g]*deltaGrad[g];
			dxSq += deltaX[g]*deltaX[g];
			dgSq += deltaGrad[g]*deltaGrad[g];
		    }
		    else // Only newly bounded variables will be non-zero
			newlyBounded +=  deltaX[g]*(grad[g]-oldGrad[g]);
		    
		    // Note: CANNOT use projected gradient for testing 
		    // convergence because of newly bounded variables
		    double tmp = Math.abs(grad[g])*
			Math.max(Math.abs(direct[g]),1.0)/
			Math.max(Math.abs(m_f),1.0);
		    if(tmp > test) test = tmp;	
		}
		
		if(test < m_Zero){
		    if (m_Debug)
			System.err.println("Gradient converge: "+test);
		    finish = true;
		}	    
		
		// dg'*dx could be < 0 using inexact lnsrch
		if(m_Debug)
		    System.err.println("dg'*dx="+(denom+newlyBounded));	
		// dg'*dx = 0
		if(Math.abs(denom+newlyBounded) < m_Zero)
		    finish = true;
		
		int size = wsBdsIndx.size();
		boolean isUpdate = true;  // Whether to update BFGS formula	    
		// Converge: check whether release any current constraints
		if(finish){
		    if (m_Debug)
			System.err.println("Test any release possible ...");
		    	
		    if(toFree != null)
			oldToFree = (FastVector)toFree.copy();
		    toFree = new FastVector();
		    
		    for(int m=size-1; m>=0; m--){
			int index=((Integer)wsBdsIndx.elementAt(m)).intValue();
			double[] hessian = evaluateHessian(x, index);			
			double deltaL=0.0;
			if(hessian != null){
			    for(int mm=0; mm<hessian.length; mm++)
				if(!isFixed[mm]) // Free variable
				    deltaL += hessian[mm]*direct[mm];
			}
			
			// First and second order Lagrangian multiplier estimate
			// If user didn't provide Hessian, use first-order only
			double L1, L2;
			if(x[index] >= constraints[1][index]) // Upper bound
			    L1 = -grad[index];
			else if(x[index] <= constraints[0][index])// Lower bound
			    L1 = grad[index];
			else
			    throw new Exception("x["+index+"] not fixed on the"+
						" bounds where it should have been!");
			
			// L2 = L1 + deltaL
			L2 = L1 + deltaL;			
			if (m_Debug)
			    System.err.println("Variable "+index+
					       ": Lagrangian="+L1+"|"+L2);
			
			//Check validity of Lagrangian multiplier estimate
			boolean isConverge = 
			    (2.0*Math.abs(deltaL)) < Math.min(Math.abs(L1),
							      Math.abs(L2));  
			if((L1*L2>0.0) && isConverge){ //Same sign and converge: valid
			    if(L2 < 0.0){// Negative Lagrangian: feasible
				toFree.addElement(new Integer(index));
				wsBdsIndx.removeElementAt(m);
				finish=false; // Not optimal, cannot finish
			    }
			}
			
			// Although hardly happen, better check it
			// If the first-order Lagrangian multiplier estimate is wrong,
			// avoid zigzagging
			if((hessian==null) && equal(toFree, oldToFree)) 
			    finish = true;           
		    }
		    
		    if(finish){// Min. found
			if (m_Debug)
			    System.err.println("Minimum found.");
			m_f = objectiveFunction(x);
			return x;
		    }
		    
		    // Free some variables
		    for(int mmm=0; mmm<toFree.size(); mmm++){
			int freeIndx=((Integer)toFree.elementAt(mmm)).intValue();
			isFixed[freeIndx] = false; // Free this variable
			if(x[freeIndx] <= constraints[0][freeIndx]){// Lower bound
			    nwsBounds[0][freeIndx] = constraints[0][freeIndx];
			    if (m_Debug)
				System.err.println("Free variable "+freeIndx+
						   " from bound "+ 
						   nwsBounds[0][freeIndx]);
			}
			else{ // Upper bound
			    nwsBounds[1][freeIndx] = constraints[1][freeIndx];
			    if (m_Debug)
				System.err.println("Free variable "+freeIndx+
						   " from bound "+ 
						   nwsBounds[1][freeIndx]);
			}			
			L.setElement(freeIndx, freeIndx, 1.0);
			D.setElement(freeIndx, freeIndx, 1.0);
			isUpdate = false;			
		    }			
		}
		
		if(denom<Math.max(m_Zero*Math.sqrt(dxSq)*Math.sqrt(dgSq), m_Zero)){
		    if (m_Debug) 
			System.err.println("dg'*dx negative!");
		    isUpdate = false; // Do not update		    
		}		
		// If Hessian will be positive definite, update it
		if(isUpdate){
		    double[] v= new double[l];  
		    Matrix[] result;
	    
		    // modify once: dg*dg'/(dg'*dx)	
		    double coeff = 1.0/denom; // 1/(dg'*dx)	
		    result = updateCholeskyFactor(L,D,deltaGrad,coeff,isFixed);
		    
		    // modify twice: g*g'/(g'*p)	
		    coeff = 1.0/m_Slope; // 1/(g'*p)
		    result=updateCholeskyFactor
			(result[0],result[1],oldGrad,coeff,isFixed);  
		    
		    L = result[0];
		    D = result[1];
		}
	    }
	    
	    // Find new direction 
	    Matrix LD = new Matrix(l,l); // L*D
	    double[] b = new double[l];
	    
	    for(int k=0; k<l; k++){
		if(!isFixed[k])  b[k] = -grad[k];
		else             b[k] = 0.0;
		
		for(int j=k; j<l; j++){ // Lower triangle	
		    if(!isFixed[j] && !isFixed[k])
			LD.setElement(j, k, L.getElement(j,k)*D.getElement(k,k));
		}		
	    }	    	
	    
	    // Solve (LD)*y = -g, where y=L'*direct
	    double[] LDIR = solveTriangle(LD, b, true, isFixed);
	    
	    for(int m=0; m<LDIR.length; m++){
		if(Double.isNaN(LDIR[m]))
		    throw new Exception("L*direct["+m+"] is NaN!"
					+"|-g="+b[m]+"|"+isFixed[m]
					+"|diag="+D.getElement(m,m));
	    }
	    
	    // Solve L'*direct = y
	    direct = solveTriangle(L, LDIR, false, isFixed);
	    for(int m=0; m<direct.length; m++){
		if(Double.isNaN(direct[m]))
		    throw new Exception("direct is NaN!");
	    }
	    
	    System.gc();
	}
	
	if(m_Debug)
	    System.err.println("Cannot find minimum"+
			       " -- too many interations!");
	m_X = x;
	return null;
    }
    
    /** 
     * Solve the linear equation of TX=B where T is a triangle matrix
     * It can be solved using back/forward substitution, with O(N^2) 
     * complexity
     * @param t the matrix T
     * @param b the vector B
     * @param isLower whether T is a lower or higher triangle matrix
     * @param isZero which row(s) of T are not used when solving the equation. 
     *               If it's null or all 'false', then every row is used.
     * @return the solution of X
     */     
    public static double[] solveTriangle(Matrix t, double[] b, 
					 boolean isLower, boolean[] isZero){
	int n = b.length; 
	double[] result = new double[n];
	if(isZero == null)
	    isZero = new boolean[n];
	
	if(isLower){ // lower triangle, forward-substitution
	    int j = 0;
	    while((j<n)&&isZero[j]){result[j]=0.0; j++;} // go to the first row
	    
	    if(j<n){
		result[j] = b[j]/t.getElement(j,j);
		
		for(; j<n; j++){
		    if(!isZero[j]){
			double numerator=b[j];
			for(int k=0; k<j; k++)
			    numerator -= t.getElement(j,k)*result[k];
			result[j] = numerator/t.getElement(j,j);
		    }
		    else 
			result[j] = 0.0;
		}
	    }
	}
	else{ // Upper triangle, back-substitution
	    int j=n-1;
	    while((j>=0)&&isZero[j]){result[j]=0.0; j--;} // go to the last row
	    
	    if(j>=0){
		result[j] = b[j]/t.getElement(j,j);
		
		for(; j>=0; j--){
		    if(!isZero[j]){
			double numerator=b[j];
			for(int k=j+1; k<n; k++)
			    numerator -= t.getElement(k,j)*result[k];
			result[j] = numerator/t.getElement(j,j);
		    }
		    else 
			result[j] = 0.0;
		}
	    }
	}
	
	return result;
    }

    /**
     * One rank update of the Cholesky factorization of B matrix in BFGS updates,
     * i.e. B = LDL', and B_{new} = LDL' + coeff*(vv') where L is a unit lower triangle
     * matrix and D is a diagonal matrix, and v is a vector.<br>
     * When coeff > 0, we use C1 algorithm, and otherwise we use C2 algorithm described
     * in ``Methods for Modifying Matrix Factorizations'' 
     *
     * @param L the unit triangle matrix L
     * @param D the diagonal matrix D
     * @param v the update vector v
     * @param coeff the coeffcient of update
     * @param isFixed which variables are not to be updated
     * @return the updated L and D
     */    
    protected Matrix[] updateCholeskyFactor(Matrix L, Matrix D, 
					    double[] v, double coeff,
					    boolean[] isFixed)
	throws Exception{
	double t, p, b;
	int n = v.length;
	double[] vp =  new double[n];
	Matrix dBar = new Matrix(n, n), lBar = new Matrix(n, n);	
	for (int i=0; i<v.length; i++)
	    if(!isFixed[i])
		vp[i]=v[i];
	    else
		vp[i]=0.0;
	
	if(coeff>0.0){
	    t = coeff;	    
	    for(int j=0; j<n; j++){		
		if(isFixed[j]) continue;
		
		lBar.setElement(j, j, 1.0); // Unit triangle
		
		p = vp[j];
		double d=D.getElement(j,j), dbarj=d+t*p*p;
		dBar.setElement(j, j, dbarj);
		
		b = p*t/dbarj;
		t *= d/dbarj;
		for(int r=j+1; r<n; r++){
		    if(!isFixed[r]){
			double l=L.getElement(r, j);
			vp[r] -= p*l;
			lBar.setElement(r, j, l+b*vp[r]);
		    }
		    else
		    	lBar.setElement(r, j, 0.0);
		}
	    }
	}
	else{
	    double[] P = solveTriangle(L, v, true, isFixed);	    
	    t = 0.0;
	    for(int i=0; i<n; i++)
		if(!isFixed[i])
		    t += P[i]*P[i]/D.getElement(i,i);	    	
	    
	    double sqrt=1.0+coeff*t;
	    sqrt = (sqrt<0.0)? 0.0 : Math.sqrt(sqrt);
	    
	    double alpha=coeff, sigma=coeff/(1.0+sqrt), rho, theta;
	    
	    for(int j=0; j<n; j++){
		if(isFixed[j]) continue;
		
		lBar.setElement(j, j, 1.0); // Unit triangle
		
		double d=D.getElement(j,j);
		p = P[j]*P[j]/d;
		theta = 1.0+sigma*p;
		t -= p; 
		if(t<0.0) t=0.0; // Rounding error

		double plus = sigma*sigma*p*t;
		if((j<n-1) && (plus <= m_Zero)) 
		    plus=m_Zero; // Avoid rounding error
		rho = theta*theta + plus;		
		dBar.setElement(j, j, rho*d);
		
		if(Double.isNaN(dBar.getElement(j,j))){
		    throw new Exception("d["+j+"] NaN! P="+P[j]+",d="+d+
					",t="+t+",p="+p+",sigma="+sigma+
					",sclar="+coeff);
		}
		
		b=alpha*P[j]/(rho*d);
		alpha /= rho;
		rho = Math.sqrt(rho);
		double sigmaOld = sigma;
		sigma *= (1.0+rho)/(rho*(theta+rho));	 
		if((j<n-1) && 
		   (Double.isNaN(sigma) || Double.isInfinite(sigma)))
		    throw new Exception("sigma NaN/Inf! rho="+rho+
				       ",theta="+theta+",P["+j+"]="+
				       P[j]+",p="+p+",d="+d+",t="+t+
				       ",oldsigma="+sigmaOld);
		
		for(int r=j+1; r<n; r++){
		    if(!isFixed[r]){
			double l=L.getElement(r, j);
			vp[r] -= P[j]*l;
			lBar.setElement(r, j, l+b*vp[r]);
		    }
		    else
		    	lBar.setElement(r, j, 0.0);
		}
	    }
	}
	
	Matrix[] rt = new Matrix[2];
	rt[0] = lBar; rt[1] = dBar;
	return rt;
    }

    /**
     * Check whether the two integer vectors equal to each other
     * Two integer vectors are equal if all the elements are the 
     * same, regardless of the order of the elements
     *
     * @param a one integer vector
     * @param b another integer vector
     * @return whether they are equal
     */ 
    private boolean equal(FastVector a, FastVector b){
	if((a==null) || (b==null) || (a.size()!=b.size()))
	    return false;
	
	int size=a.size();
	// Store into int arrays
	int[] ia=new int[size], ib=new int[size];
	for(int i=0;i<size;i++){
	    ia[i] = ((Integer)a.elementAt(i)).intValue();
	    ib[i] = ((Integer)b.elementAt(i)).intValue();
	}
	// Only values matter, order does not matter
	int[] sorta=Utils.sort(ia), sortb=Utils.sort(ib);
	for(int j=0; j<size;j++)
	    if(ia[sorta[j]] != ib[sortb[j]])
		return false;
	
	return true;
    }
}
