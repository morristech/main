package org.conserve.tools.metadata;

/**
 * 
 * Describes a change in a property.
 * 
 * @author Erik Berglund
 *
 */
public class ChangeDescription
{
	private String fromName;
	private String toName;
	private Class<?> fromClass;
	private Class<?> toClass;
	private boolean isIndexChange = false;
	
	/**
	 * @return the fromName
	 */
	public String getFromName()
	{
		return fromName;
	}
	/**
	 * @param fromName the fromName to set
	 */
	public void setFromName(String fromName)
	{
		this.fromName = fromName;
	}
	/**
	 * @return the toName
	 */
	public String getToName()
	{
		return toName;
	}
	/**
	 * @param toName the toName to set
	 */
	public void setToName(String toName)
	{
		this.toName = toName;
	}
	/**
	 * @return the fromClass
	 */
	public Class<?> getFromClass()
	{
		return fromClass;
	}
	/**
	 * @param fromClass the fromClass to set
	 */
	public void setFromClass(Class<?> fromClass)
	{
		this.fromClass = fromClass;
	}
	/**
	 * @return the toClass
	 */
	public Class<?> getToClass()
	{
		return toClass;
	}
	/**
	 * @param toClass the toClass to set
	 */
	public void setToClass(Class<?> toClass)
	{
		this.toClass = toClass;
	}
	
	public boolean isDeletion()
	{
		return !isIndexChange & toName == null;
	}
	
	public boolean isCreation()
	{
		return !isIndexChange & fromName == null;
	}
	
	public boolean isNameChange()
	{
		return !isDeletion() && !isCreation() && toName != null && !toName.equals(fromName);
	}
	
	public boolean isTypeChange()
	{
		return !isDeletion() && !isCreation() && !isNameChange() && toClass!=null && !toClass.equals(fromClass);
	}
	/**
	 * 
	 */
	public void setIsIndexChange()
	{
		isIndexChange = true;
	}
	
	public boolean isIndexChange()
	{
		return this.isIndexChange;
	}
}
