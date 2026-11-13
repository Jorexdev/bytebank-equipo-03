# Convenciones de codificación

- **Siempre** usar nombres en español para variables, métodos y clases.
- **Siempre** escribir el codigo en secuencias legibles y entendibles evitando abreviar o meter codigo demasiado grande
  en un solo metodo o constructor.
- **Siempre** usar nombres de variables, métodos y clases que sean descriptivos y representen claramente su propósito.
- **Siempre** usar inyeccion de dependencias por constructor.
- **Siempre** usar `ResponseEntity` para devolver respuestas HTTP en los controladores.

# Convenciones de Git

- **Siempre** usar nombres de ramas en español y que representen claramente el objetivo de la rama.
- **Siempre** usar nombres de commits en español y que representen claramente el objetivo del commit.
- **Siempre** usar conventional commits para los mensajes de commit, siguiendo el formato:
  `<tipo>(<área>): <descripción>`.
- **Nunca** hacer merges de ramas directamente a `main` o `develop`. Siempre crear un Pull Request y que sea revisado
  por al menos un miembro del equipo antes de hacer merge.
- **Nunca** realizar el Pull Request delegar dicha tarea a un miembro del equipo que no sea el autor del código. El
  autor del código es el responsable de crear el Pull Request y de asegurarse de que se cumplan las convenciones de
  codificación y Git.
- **Nunca** agregar comentarios de Co-Author de IA en los commits. Los autores del código deben ser reconocidos en el
  Pull Request y no en los commits individuales.
- **Siempre** reaalizar commits pequeños y atómicos que representen cambios específicos y coherentes en el código.
  Evitar commits grandes que mezclen múltiples cambios o funcionalidades.

# Convenciones de trabajo

- **Siempre** hacer Pair Programming para refactorizaciones de código y para la implementación de nuevas
  funcionalidades. Esto asegura que el código sea revisado por al menos un miembro del equipo antes de ser mergeado a
  `main` o `develop`.