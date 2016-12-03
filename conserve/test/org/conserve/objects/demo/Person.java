package org.conserve.objects.demo;

/**
 * Test object to check that the examples in the tutorial work.
 * @author Erik Berglund
 *
 */
public class Person
{
	private String firstName;
	private String lastName;
	public Person()
	{
	}
	public Person(String firstName,String lastName)
	{
		this.firstName=firstName;
		this.lastName=lastName;
	}
	public String getLastName()
	{
		return lastName;
	}
	public void setLastName(String lastName)
	{
		this.lastName = lastName;
	}
	public String getFirstName()
	{
		return firstName;
	}
	public void setFirstName(String firstName)
	{
		this.firstName = firstName;
	}
}
